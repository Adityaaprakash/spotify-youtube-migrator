package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionAuditEvent;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionAuditEventType;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionReconciliationRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionRollbackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionAuditEventRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Default implementation of {@link ExecutionAuditService} (Phase 14D).
 * <p>
 * Durably records append-only execution lifecycle transitions, retry events, worker lease actions,
 * and compensating operations. Enforces strict anti-IDOR security on timeline retrieval.
 */
@Service
@Transactional
public class DefaultExecutionAuditService implements ExecutionAuditService {

    private static final Logger log = LoggerFactory.getLogger(DefaultExecutionAuditService.class);

    private final ExecutionAuditEventRepository auditEventRepository;
    private final MigrationExecutionRepository executionRepository;

    @Autowired
    public DefaultExecutionAuditService(
            ExecutionAuditEventRepository auditEventRepository,
            MigrationExecutionRepository executionRepository
    ) {
        this.auditEventRepository = Objects.requireNonNull(auditEventRepository, "ExecutionAuditEventRepository must not be null");
        this.executionRepository = Objects.requireNonNull(executionRepository, "MigrationExecutionRepository must not be null");
    }

    @Override
    public ExecutionAuditEvent recordStateTransition(
            MigrationExecution execution,
            MigrationExecutionStatus previousState,
            MigrationExecutionStatus newState,
            String reason,
            String actor,
            String correlationId
    ) {
        Objects.requireNonNull(execution, "MigrationExecution must not be null");
        Objects.requireNonNull(newState, "New MigrationExecutionStatus must not be null");

        ExecutionAuditEvent event = ExecutionAuditEvent.stateTransition(
                execution,
                previousState,
                newState,
                reason,
                actor,
                correlationId
        );
        ExecutionAuditEvent saved = auditEventRepository.save(event);
        log.info("Recorded state transition audit event for execution {}: {} -> {} (actor={})",
                execution.getId(), previousState, newState, actor);
        return saved;
    }

    @Override
    public ExecutionAuditEvent recordStateTransition(
            UUID executionId,
            MigrationExecutionStatus previousState,
            MigrationExecutionStatus newState,
            String reason,
            String actor,
            String correlationId
    ) {
        if (executionId == null) {
            return null;
        }
        MigrationExecution execution = executionRepository.findById(executionId).orElse(null);
        if (execution == null) {
            log.warn("Cannot record state transition audit event for nonexistent execution {}", executionId);
            return null;
        }
        return recordStateTransition(execution, previousState, newState, reason, actor, correlationId);
    }

    @Override
    public ExecutionAuditEvent recordRetryEvent(
            MigrationExecution execution,
            int attemptCount,
            long backoffMs,
            String failureClassification,
            String reason,
            String actor,
            String correlationId
    ) {
        Objects.requireNonNull(execution, "MigrationExecution must not be null");

        ExecutionAuditEvent event = ExecutionAuditEvent.retryScheduled(
                execution,
                attemptCount,
                backoffMs,
                failureClassification,
                reason,
                actor,
                correlationId
        );
        ExecutionAuditEvent saved = auditEventRepository.save(event);
        log.info("Recorded retry audit event for execution {}: attempt={}, backoffMs={} (actor={})",
                execution.getId(), attemptCount, backoffMs, actor);
        return saved;
    }

    @Override
    public ExecutionAuditEvent recordLeaseEvent(
            MigrationExecution execution,
            ExecutionAuditEventType eventType,
            String workerId,
            long leaseGeneration,
            String reason
    ) {
        Objects.requireNonNull(execution, "MigrationExecution must not be null");
        Objects.requireNonNull(eventType, "ExecutionAuditEventType must not be null");

        ExecutionAuditEvent event = ExecutionAuditEvent.leaseEvent(
                execution,
                eventType,
                workerId,
                leaseGeneration,
                reason
        );
        ExecutionAuditEvent saved = auditEventRepository.save(event);
        log.info("Recorded lease audit event for execution {}: type={}, worker={}, gen={}",
                execution.getId(), eventType, workerId, leaseGeneration);
        return saved;
    }

    @Override
    public ExecutionAuditEvent recordLeaseEvent(
            UUID executionId,
            ExecutionAuditEventType eventType,
            String workerId,
            long leaseGeneration,
            String reason
    ) {
        if (executionId == null) {
            return null;
        }
        MigrationExecution execution = executionRepository.findById(executionId).orElse(null);
        if (execution == null) {
            log.warn("Cannot record lease event for nonexistent execution {}", executionId);
            return null;
        }
        return recordLeaseEvent(execution, eventType, workerId, leaseGeneration, reason);
    }

    @Override
    public ExecutionAuditEvent recordRollbackEvent(
            MigrationExecution execution,
            ExecutionAuditEventType eventType,
            ExecutionRollbackRecord rollbackRecord,
            String reason,
            String actor
    ) {
        Objects.requireNonNull(execution, "MigrationExecution must not be null");
        Objects.requireNonNull(eventType, "ExecutionAuditEventType must not be null");

        ExecutionAuditEvent event = ExecutionAuditEvent.rollbackEvent(
                execution,
                eventType,
                rollbackRecord,
                reason,
                actor
        );
        ExecutionAuditEvent saved = auditEventRepository.save(event);
        log.info("Recorded rollback audit event for execution {}: type={}, actor={}",
                execution.getId(), eventType, actor);
        return saved;
    }

    @Override
    public ExecutionAuditEvent recordReconciliationEvent(
            MigrationExecution execution,
            ExecutionReconciliationRecord reconciliationRecord,
            String reason,
            String actor
    ) {
        Objects.requireNonNull(execution, "MigrationExecution must not be null");

        ExecutionAuditEvent event = ExecutionAuditEvent.reconciliationEvent(
                execution,
                reconciliationRecord,
                reason,
                actor
        );
        ExecutionAuditEvent saved = auditEventRepository.save(event);
        log.info("Recorded reconciliation audit event for execution {}: actor={}",
                execution.getId(), actor);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ExecutionAuditEvent> getAuditTimeline(UUID executionId, UUID authenticatedUserId, Pageable pageable) {
        verifyExecutionOwnership(executionId, authenticatedUserId);
        return auditEventRepository.findByExecutionIdOrderByCreatedAtAsc(executionId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExecutionAuditEvent> getAuditTimeline(UUID executionId, UUID authenticatedUserId) {
        verifyExecutionOwnership(executionId, authenticatedUserId);
        return auditEventRepository.findByExecutionIdOrderByCreatedAtAsc(executionId);
    }

    private void verifyExecutionOwnership(UUID executionId, UUID authenticatedUserId) {
        if (executionId == null) {
            throw new IllegalArgumentException("Execution ID must not be null");
        }
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user ID must not be null");
        }

        MigrationExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Migration execution not found: " + executionId));

        if (!execution.getUser().getId().equals(authenticatedUserId)) {
            // Anti-IDOR: Prevent disclosure of foreign migration existence
            throw new ResourceNotFoundException("Migration execution not found: " + executionId);
        }
    }
}
