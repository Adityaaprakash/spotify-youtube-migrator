package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionAuditEvent;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionAuditEventType;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionReconciliationRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionRollbackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * Application service for durable execution audit logging, state transition history,
 * retry tracking, worker lease auditing, and timeline inspection (Phase 14D).
 */
public interface ExecutionAuditService {

    /**
     * Records a lifecycle state transition for a migration execution.
     *
     * @param execution the migration execution aggregate
     * @param previousState preceding status (nullable for creation)
     * @param newState new target status
     * @param reason human-readable justification or context
     * @param actor user, worker node, or system component triggering the transition
     * @param correlationId optional operation or idempotency correlation ID
     * @return persisted audit event
     */
    ExecutionAuditEvent recordStateTransition(
            MigrationExecution execution,
            MigrationExecutionStatus previousState,
            MigrationExecutionStatus newState,
            String reason,
            String actor,
            String correlationId
    );

    /**
     * Records a lifecycle state transition by execution ID.
     *
     * @param executionId execution identifier
     * @param previousState preceding status (nullable for creation)
     * @param newState new target status
     * @param reason human-readable justification or context
     * @param actor user, worker node, or system component triggering the transition
     * @param correlationId optional operation or idempotency correlation ID
     * @return persisted audit event or null if execution not found
     */
    ExecutionAuditEvent recordStateTransition(
            UUID executionId,
            MigrationExecutionStatus previousState,
            MigrationExecutionStatus newState,
            String reason,
            String actor,
            String correlationId
    );

    /**
     * Records a retry scheduling or execution event.
     *
     * @param execution the migration execution aggregate
     * @param attemptCount retry attempt index
     * @param backoffMs backoff interval in milliseconds
     * @param failureClassification provider failure classification
     * @param reason failure description
     * @param actor worker or scheduler actor
     * @param correlationId optional correlation identifier
     * @return persisted audit event
     */
    ExecutionAuditEvent recordRetryEvent(
            MigrationExecution execution,
            int attemptCount,
            long backoffMs,
            String failureClassification,
            String reason,
            String actor,
            String correlationId
    );

    /**
     * Records a worker lease lifecycle event (acquisition, release, expiry, or rejection compensation).
     *
     * @param execution the migration execution aggregate
     * @param eventType lease event type (LEASE_ACQUIRED, LEASE_RELEASED, LEASE_EXPIRED)
     * @param workerId worker node identifier
     * @param leaseGeneration generational lease token
     * @param reason context description
     * @return persisted audit event
     */
    ExecutionAuditEvent recordLeaseEvent(
            MigrationExecution execution,
            ExecutionAuditEventType eventType,
            String workerId,
            long leaseGeneration,
            String reason
    );

    /**
     * Records a worker lease lifecycle event by execution ID (acquisition, release, expiry, or rejection compensation).
     *
     * @param executionId migration execution identifier
     * @param eventType lease event type (LEASE_ACQUIRED, LEASE_RELEASED, LEASE_EXPIRED)
     * @param workerId worker node identifier
     * @param leaseGeneration generational lease token
     * @param reason context description
     * @return persisted audit event or null if execution not found
     */
    ExecutionAuditEvent recordLeaseEvent(
            UUID executionId,
            ExecutionAuditEventType eventType,
            String workerId,
            long leaseGeneration,
            String reason
    );

    /**
     * Records a compensating rollback audit event.
     *
     * @param execution the migration execution aggregate
     * @param eventType ROLLBACK_REQUESTED or ROLLBACK_COMPLETED
     * @param rollbackRecord associated immutable rollback audit record
     * @param reason context description
     * @param actor initiator of rollback
     * @return persisted audit event
     */
    ExecutionAuditEvent recordRollbackEvent(
            MigrationExecution execution,
            ExecutionAuditEventType eventType,
            ExecutionRollbackRecord rollbackRecord,
            String reason,
            String actor
    );

    /**
     * Records a destination reconciliation audit event.
     *
     * @param execution the migration execution aggregate
     * @param reconciliationRecord associated immutable reconciliation audit record
     * @param reason inspection summary
     * @param actor inspector actor
     * @return persisted audit event
     */
    ExecutionAuditEvent recordReconciliationEvent(
            MigrationExecution execution,
            ExecutionReconciliationRecord reconciliationRecord,
            String reason,
            String actor
    );

    /**
     * Retrieves a paginated timeline of audit events for a migration execution.
     * Enforces anti-IDOR security: verifying caller ownership.
     *
     * @param executionId execution identifier
     * @param authenticatedUserId owner user ID
     * @param pageable pagination parameters
     * @return paginated chronological audit events
     */
    Page<ExecutionAuditEvent> getAuditTimeline(UUID executionId, UUID authenticatedUserId, Pageable pageable);

    /**
     * Retrieves the complete chronological audit timeline for a migration execution.
     * Enforces anti-IDOR security: verifying caller ownership.
     *
     * @param executionId execution identifier
     * @param authenticatedUserId owner user ID
     * @return chronological audit events
     */
    List<ExecutionAuditEvent> getAuditTimeline(UUID executionId, UUID authenticatedUserId);
}
