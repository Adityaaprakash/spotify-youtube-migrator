package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionReconciliationSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionRollbackSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ReconcileExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RecoverExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackExecutionRequest;

import java.util.UUID;

/**
 * High-level orchestration facade coordinating the entire Phase 11 approved migration lifecycle:
 * <ol>
 *   <li>Approved plan validation (Phase 11B)</li>
 *   <li>Destination playlist creation (Phase 11C)</li>
 *   <li>Durable execution initialization (Phase 11E)</li>
 *   <li>Ordered track insertion (Phase 11D/11E)</li>
 *   <li>Controlled failure recovery (Phase 11F)</li>
 *   <li>Safe partial rollback (Phase 11F)</li>
 *   <li>Ambiguous outcome reconciliation (Phase 11G)</li>
 * </ol>
 * <p>
 * This facade is intentionally thin: it orchestrates existing specialized domain services
 * without duplicating their validation, security, transaction, or mutation business logic.
 */
public interface ApprovedMigrationOrchestrator {

    /**
     * Executes an approved playlist migration end-to-end.
     * Validates approved review session decisions, ensures destination playlist creation,
     * initializes durable execution tracking, and performs canonical ordered track insertion.
     *
     * @param request execution request containing review session ID and optional playlist metadata
     * @param authenticatedUserId trusted authenticated user ID
     * @return summary of the durable execution and insertion outcomes
     */
    MigrationExecutionSummary executeApprovedMigration(ApprovedMigrationExecutionRequest request, UUID authenticatedUserId);

    /**
     * Convenience overload executing an approved migration by review session ID.
     *
     * @param reviewSessionId unique review session identifier
     * @param authenticatedUserId trusted authenticated user ID
     * @return summary of the durable execution and insertion outcomes
     */
    default MigrationExecutionSummary executeApprovedMigration(UUID reviewSessionId, UUID authenticatedUserId) {
        return executeApprovedMigration(ApprovedMigrationExecutionRequest.of(reviewSessionId, authenticatedUserId), authenticatedUserId);
    }

    /**
     * Prepares and persists a migration execution in QUEUED status ready for asynchronous background worker dispatch (Phase 12C).
     * Validates approved review session decisions, ensures destination playlist creation,
     * and initializes durable execution tracking in QUEUED status with approved tracks.
     *
     * @param request execution request containing review session ID and optional playlist metadata
     * @param authenticatedUserId trusted authenticated user ID
     * @return summary of the durable execution in QUEUED status
     */
    MigrationExecutionSummary prepareQueuedMigration(ApprovedMigrationExecutionRequest request, UUID authenticatedUserId);

    /**
     * Executes an already-persisted, queued playlist migration in a background worker thread (Phase 12C).
     * Atomically claims the QUEUED execution and delegates to ordered track insertion.
     *
     * @param executionId unique execution ID of the queued migration
     * @param authenticatedUserId trusted authenticated user ID
     * @return summary of the durable execution and insertion outcomes
     */
    MigrationExecutionSummary executeQueuedMigration(UUID executionId, UUID authenticatedUserId);

    /**
     * Recovers eligible failed tracks in an execution via Phase 11F controlled recovery.
     *
     * @param executionId unique execution ID
     * @param request recovery request options
     * @param authenticatedUserId trusted authenticated user ID
     * @return summary of the updated execution after recovery
     */
    MigrationExecutionSummary recoverMigration(UUID executionId, RecoverExecutionRequest request, UUID authenticatedUserId);

    /**
     * Executes safe compensating deletion of confirmed inserted tracks via Phase 11F partial rollback.
     *
     * @param executionId unique execution ID
     * @param request rollback request options
     * @param authenticatedUserId trusted authenticated user ID
     * @return summary of the rollback operations and audit outcomes
     */
    ExecutionRollbackSummary rollbackMigration(UUID executionId, RollbackExecutionRequest request, UUID authenticatedUserId);

    /**
     * Reconciles ambiguous mutation outcomes against the remote destination playlist via Phase 11G remote state verification.
     *
     * @param executionId unique execution ID
     * @param request reconciliation request options
     * @param authenticatedUserId trusted authenticated user ID
     * @return summary of the reconciliation operations and resolved track states
     */
    ExecutionReconciliationSummary reconcileMigration(UUID executionId, ReconcileExecutionRequest request, UUID authenticatedUserId);

    /**
     * Retrieves the durable execution summary by execution ID.
     *
     * @param executionId unique execution ID
     * @param authenticatedUserId trusted authenticated user ID
     * @return durable execution summary
     */
    MigrationExecutionSummary getExecutionSummary(UUID executionId, UUID authenticatedUserId);

    /**
     * Retrieves the durable execution summary by review session ID.
     *
     * @param reviewSessionId unique review session ID
     * @param authenticatedUserId trusted authenticated user ID
     * @return durable execution summary
     */
    MigrationExecutionSummary getExecutionSummaryByReviewSession(UUID reviewSessionId, UUID authenticatedUserId);
}
