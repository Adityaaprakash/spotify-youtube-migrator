package com.spotifyyoutube.migrator.migration.async;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.migration.application.execution.ApprovedMigrationOrchestrator;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Background worker component responsible for processing persistent QUEUED or claimed migrations (Phase 12C, Phase 13D-C).
 * <p>
 * Invoked asynchronously by {@link org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor} worker threads.
 * Delegates strictly into {@link ApprovedMigrationOrchestrator} to preserve existing Phase 11 track insertion,
 * per-track short transactions, outcome reconciliation, and failure handling.
 */
@Component
public class MigrationBackgroundWorker {

    private static final Logger log = LoggerFactory.getLogger(MigrationBackgroundWorker.class);

    private final ApprovedMigrationOrchestrator orchestrator;
    private final MigrationExecutionRepository executionRepository;

    @Autowired
    public MigrationBackgroundWorker(
            ApprovedMigrationOrchestrator orchestrator,
            @Autowired(required = false) MigrationExecutionRepository executionRepository
    ) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "ApprovedMigrationOrchestrator must not be null");
        this.executionRepository = executionRepository;
    }

    public MigrationBackgroundWorker(ApprovedMigrationOrchestrator orchestrator) {
        this(orchestrator, null);
    }

    /**
     * Processes an already-persisted QUEUED migration execution on the current background worker thread.
     *
     * @param executionId durable execution identifier
     * @param authenticatedUserId ID of the authenticated user owning the execution
     */
    public void processMigration(UUID executionId, UUID authenticatedUserId) {
        if (executionId == null) {
            throw new IllegalArgumentException("Execution ID must not be null");
        }
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user ID must not be null");
        }

        Instant startTime = Instant.now();
        log.info("Background worker started processing migration execution {} for user {}", executionId, authenticatedUserId);

        try {
            MigrationExecutionSummary summary = orchestrator.executeQueuedMigration(executionId, authenticatedUserId);
            Duration duration = Duration.between(startTime, Instant.now());
            log.info("Background worker completed migration execution {} in {}ms with status {} (successful: {}, failed: {}, uncertain: {}, skipped: {})",
                    executionId, duration.toMillis(), summary.status(),
                    summary.successfulInsertions(), summary.failedInsertions(),
                    summary.uncertainInsertions(), summary.skippedInsertions());
        } catch (InvalidStateException ex) {
            log.warn("Migration execution {} rejected by orchestrator during background processing: {}",
                    executionId, ex.getMessage());
        } catch (ResourceNotFoundException ex) {
            log.error("Migration execution {} not found or access denied during background processing: {}",
                    executionId, ex.getMessage());
        } catch (Exception ex) {
            Duration duration = Duration.between(startTime, Instant.now());
            log.error("Unexpected error in background worker processing migration execution {} after {}ms: {}",
                    executionId, duration.toMillis(), ex.getMessage(), ex);
        }
    }

    /**
     * Processes an already-claimed migration execution under the acquired fencing generation (Phase 13D-C).
     *
     * @param executionId durable execution identifier
     * @param authenticatedUserId ID of the authenticated user owning the execution
     * @param leaseGeneration acquired fencing generation
     */
    public void processExecution(UUID executionId, UUID authenticatedUserId, long leaseGeneration) {
        if (executionId == null) {
            throw new IllegalArgumentException("Execution ID must not be null");
        }
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user ID must not be null");
        }

        Instant startTime = Instant.now();
        log.info("Background worker started processing claimed migration execution {} (generation {}) for user {}",
                executionId, leaseGeneration, authenticatedUserId);

        try {
            MigrationExecutionSummary summary = orchestrator.executeClaimedMigration(
                    executionId, authenticatedUserId, leaseGeneration);
            Duration duration = Duration.between(startTime, Instant.now());
            log.info("Background worker completed claimed execution {} in {}ms with status {} "
                            + "(successful: {}, failed: {}, uncertain: {}, skipped: {})",
                    executionId, duration.toMillis(), summary.status(),
                    summary.successfulInsertions(), summary.failedInsertions(),
                    summary.uncertainInsertions(), summary.skippedInsertions());
        } catch (InvalidStateException ex) {
            log.warn("Claimed migration execution {} rejected during background processing: {}",
                    executionId, ex.getMessage());
        } catch (ResourceNotFoundException ex) {
            log.error("Claimed migration execution {} not found or access denied during background processing: {}",
                    executionId, ex.getMessage());
        } catch (Exception ex) {
            Duration duration = Duration.between(startTime, Instant.now());
            log.error("Unexpected error in background worker processing claimed migration execution {} after {}ms: {}",
                    executionId, duration.toMillis(), ex.getMessage(), ex);
        }
    }

    /**
     * Convenience overload processing a claimed migration execution by resolving owner user ID (Phase 13D-C).
     *
     * @param executionId durable execution identifier
     * @param leaseGeneration acquired fencing generation
     */
    public void processExecution(UUID executionId, long leaseGeneration) {
        if (executionId == null) {
            throw new IllegalArgumentException("Execution ID must not be null");
        }
        if (executionRepository == null) {
            throw new IllegalStateException(
                    "MigrationExecutionRepository is required to resolve user for execution " + executionId);
        }
        UUID userId = executionRepository.findById(executionId)
                .map(exec -> exec.getUser().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Migration execution not found: " + executionId));
        processExecution(executionId, userId, leaseGeneration);
    }
}

