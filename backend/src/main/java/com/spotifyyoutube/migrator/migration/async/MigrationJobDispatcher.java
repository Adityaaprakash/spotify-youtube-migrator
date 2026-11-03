package com.spotifyyoutube.migrator.migration.async;

import com.spotifyyoutube.migrator.migration.application.execution.ApprovedMigrationOrchestrator;
import com.spotifyyoutube.migrator.migration.application.execution.MigrationExecutionStateService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Application service coordinating approved migration preparation and asynchronous background dispatch (Phase 12C).
 * <p>
 * Two-stage execution pattern:
 * <ol>
 *   <li>Stage 1 (Synchronous Preparation): Validates approved review session decisions, ensures YouTube destination
 *       playlist creation, and persists {@link com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution}
 *       in QUEUED status with approved track records.</li>
 *   <li>Stage 2 (Background Dispatch): Submits the persistent execution ID to the bounded {@link TaskExecutor}.
 *       If the worker pool is saturated (queue capacity exhausted), handles rejection by safely resetting the execution
 *       back to INITIALIZED to prevent orphaning.</li>
 * </ol>
 */
@Service
public class MigrationJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MigrationJobDispatcher.class);

    private final ApprovedMigrationOrchestrator orchestrator;
    private final MigrationExecutionStateService executionStateService;
    private final MigrationBackgroundWorker worker;
    private final TaskExecutor taskExecutor;

    public MigrationJobDispatcher(
            ApprovedMigrationOrchestrator orchestrator,
            MigrationExecutionStateService executionStateService,
            MigrationBackgroundWorker worker,
            @Qualifier(AsyncMigrationConfig.MIGRATION_TASK_EXECUTOR_BEAN) TaskExecutor taskExecutor
    ) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "ApprovedMigrationOrchestrator must not be null");
        this.executionStateService = Objects.requireNonNull(executionStateService, "MigrationExecutionStateService must not be null");
        this.worker = Objects.requireNonNull(worker, "MigrationBackgroundWorker must not be null");
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "TaskExecutor must not be null");
    }

    /**
     * Prepares and dispatches an approved playlist migration for asynchronous background processing.
     *
     * @param request approved migration request containing review session ID and optional playlist metadata
     * @param authenticatedUserId trusted authenticated user ID
     * @return summary of the persisted migration in QUEUED (or COMPLETED if already complete) status
     * @throws TaskRejectedException if the bounded worker pool queue is saturated
     */
    public MigrationExecutionSummary dispatchMigration(ApprovedMigrationExecutionRequest request, UUID authenticatedUserId) {
        if (request == null) {
            throw new IllegalArgumentException("ApprovedMigrationExecutionRequest must not be null");
        }
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user ID must not be null");
        }

        // Stage 1: Synchronous preparation & persistence in QUEUED status
        MigrationExecutionSummary prepared = orchestrator.prepareQueuedMigration(request, authenticatedUserId);

        // If execution is already COMPLETED (idempotent submission), return immediately without dispatch
        if (prepared.status() == MigrationExecutionStatus.COMPLETED) {
            log.info("Execution {} for review session {} is already COMPLETED. Returning without dispatching worker.",
                    prepared.executionId(), request.reviewSessionId());
            return prepared;
        }

        // Stage 2: Background worker dispatch
        UUID executionId = prepared.executionId();
        try {
            taskExecutor.execute(() -> worker.processMigration(executionId, authenticatedUserId));
            log.info("Successfully dispatched migration execution {} to worker pool for user {}", executionId, authenticatedUserId);
            return prepared;
        } catch (TaskRejectedException ex) {
            log.error("Worker pool queue capacity saturated. Dispatch rejected for execution {}: {}",
                    executionId, ex.getMessage());
            // Controlled rejection handling: reset to INITIALIZED so execution is not orphaned in QUEUED
            executionStateService.handleDispatchRejection(executionId, authenticatedUserId, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.error("Unexpected error dispatching migration execution {} to worker pool: {}",
                    executionId, ex.getMessage(), ex);
            executionStateService.handleDispatchRejection(executionId, authenticatedUserId, ex.getMessage());
            throw ex;
        }
    }
}
