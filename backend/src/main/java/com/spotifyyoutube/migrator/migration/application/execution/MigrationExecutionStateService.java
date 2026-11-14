package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.migration.application.execution.contract.InitializeExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;

import java.util.UUID;

/**
 * Application service managing durable migration execution lifecycle, per-track state persistence,
 * concurrency claims, and safe resume operations (Phase 11E).
 */
public interface MigrationExecutionStateService {

    /**
     * Initializes a durable migration execution record from an approved review session and destination playlist.
     * Idempotent: returns existing execution if one was already initialized for the review session.
     *
     * @param request execution initialization request
     * @param authenticatedUserId ID of the authenticated user
     * @return durable execution summary
     */
    MigrationExecutionSummary initializeExecution(InitializeExecutionRequest request, UUID authenticatedUserId);

    /**
     * Transitions an existing durable execution from INITIALIZED to QUEUED state for asynchronous background dispatch (Phase 12B).
     *
     * @param executionId execution identifier
     * @param authenticatedUserId ID of the authenticated user
     * @return updated durable execution summary in QUEUED status
     */
    MigrationExecutionSummary queueExecution(UUID executionId, UUID authenticatedUserId);

    /**
     * Initializes durable execution state directly in QUEUED status ready for asynchronous background dispatch (Phase 12B).
     * Idempotent: returns existing execution if one was already initialized or queued for the review session.
     *
     * @param request execution initialization request
     * @param authenticatedUserId ID of the authenticated user
     * @return durable execution summary in QUEUED status
     */
    MigrationExecutionSummary initializeQueuedExecution(InitializeExecutionRequest request, UUID authenticatedUserId);

    /**
     * Retrieves durable migration execution details and per-track records by execution ID.
     *
     * @param executionId execution identifier
     * @param authenticatedUserId ID of the authenticated user
     * @return durable execution summary
     */
    MigrationExecutionSummary getExecution(UUID executionId, UUID authenticatedUserId);

    /**
     * Retrieves durable migration execution details and per-track records by review session ID.
     *
     * @param reviewSessionId review session identifier
     * @param authenticatedUserId ID of the authenticated user
     * @return durable execution summary
     */
    MigrationExecutionSummary getExecutionByReviewSession(UUID reviewSessionId, UUID authenticatedUserId);

    /**
     * Executes track insertion for the specified durable execution.
     * Claims the execution, performs sequential mutations, commits per-track outcomes, and updates summary.
     *
     * @param executionId execution identifier
     * @param authenticatedUserId ID of the authenticated user
     * @return updated durable execution summary
     */
    MigrationExecutionSummary executeTrackInsertion(UUID executionId, UUID authenticatedUserId);

    /**
     * Executes track insertion specifically for a QUEUED migration execution (Phase 12C background worker).
     * Atomically claims the execution ONLY if current status is QUEUED.
     * Rejects non-QUEUED executions at both Java domain pre-check and database atomic conditional claim levels.
     *
     * @param executionId execution identifier
     * @param authenticatedUserId ID of the authenticated user
     * @return updated durable execution summary
     */
    MigrationExecutionSummary executeQueuedTrackInsertion(UUID executionId, UUID authenticatedUserId);

    /**
     * Executes ordered track insertion for an already-claimed execution under the
     * acquired fencing generation (Phase 13D-C).
     * Validates owner identity, RUNNING status, and exact lease generation before proceeding.
     *
     * @param executionId execution identifier
     * @param authenticatedUserId ID of the authenticated user
     * @param leaseGeneration acquired fencing generation
     * @return updated durable execution summary
     */
    MigrationExecutionSummary executeClaimedTrackInsertion(
            UUID executionId,
            UUID authenticatedUserId,
            long leaseGeneration
    );

    /**
     * Resets a QUEUED execution back to INITIALIZED status if background dispatch was rejected (Phase 12C).
     * Preserves destination playlist and track records for safe retry.
     *
     * @param executionId execution identifier
     * @param authenticatedUserId ID of the authenticated user
     * @param reason failure or rejection reason
     * @return updated durable execution summary in INITIALIZED status
     */
    MigrationExecutionSummary handleDispatchRejection(UUID executionId, UUID authenticatedUserId, String reason);

    /**
     * Initializes (if not already present) and executes track insertion for the specified approved review session.
     *
     * @param request track insertion execution request
     * @param authenticatedUserId ID of the authenticated user
     * @return updated durable execution summary
     */
    MigrationExecutionSummary executeTrackInsertion(com.spotifyyoutube.migrator.migration.application.execution.contract.InsertTracksExecutionRequest request, UUID authenticatedUserId);

    /**
     * Safely resumes an interrupted or partially completed execution for remaining eligible pending tracks.
     * Respects prior outcomes and preserves canonical ordering.
     *
     * @param executionId execution identifier
     * @param authenticatedUserId ID of the authenticated user
     * @return updated durable execution summary
     */
    MigrationExecutionSummary resumeExecution(UUID executionId, UUID authenticatedUserId);

    /**
     * Executes controlled recovery of eligible failed tracks within an execution (Phase 11F).
     *
     * @param executionId execution identifier
     * @param request recovery request specifying target parameters
     * @param authenticatedUserId ID of the authenticated user
     * @return updated execution summary
     */
    MigrationExecutionSummary recoverExecution(UUID executionId, com.spotifyyoutube.migrator.migration.application.execution.contract.RecoverExecutionRequest request, UUID authenticatedUserId);

    /**
     * Executes controlled recovery of eligible failed tracks within an execution (Phase 11F).
     *
     * @param executionId execution identifier
     * @param authenticatedUserId ID of the authenticated user
     * @return updated execution summary
     */
    MigrationExecutionSummary recoverExecution(UUID executionId, UUID authenticatedUserId);

    /**
     * Executes partial or complete rollback of inserted destination tracks (Phase 11F).
     *
     * @param executionId execution identifier
     * @param request rollback request specifying target positions
     * @param authenticatedUserId ID of the authenticated user
     * @return rollback operation summary with itemized outcomes
     */
    com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionRollbackSummary rollbackExecution(UUID executionId, com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackExecutionRequest request, UUID authenticatedUserId);

    /**
     * Executes complete rollback of all inserted destination tracks in an execution (Phase 11F).
     *
     * @param executionId execution identifier
     * @param authenticatedUserId ID of the authenticated user
     * @return rollback operation summary with itemized outcomes
     */
    com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionRollbackSummary rollbackExecution(UUID executionId, UUID authenticatedUserId);

    /**
     * Retrieves the audit history of rollback operations for an execution (Phase 11F).
     *
     * @param executionId execution identifier
     * @param authenticatedUserId ID of the authenticated user
     * @return list of rollback audit records
     */
    java.util.List<com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackRecordDto> getRollbackHistory(UUID executionId, UUID authenticatedUserId);

    /**
     * Reconciles uncertain track outcomes against the remote destination playlist state (Phase 11G).
     * Inspects destination playlist, applies evidence-driven state resolution, updates execution state,
     * and persists audit records.
     *
     * @param executionId execution identifier
     * @param request reconciliation request specifying target parameters
     * @param authenticatedUserId ID of the authenticated user
     * @return reconciliation operation summary with itemized outcomes
     */
    com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionReconciliationSummary reconcileExecution(
            UUID executionId,
            com.spotifyyoutube.migrator.migration.application.execution.contract.ReconcileExecutionRequest request,
            UUID authenticatedUserId
    );

    /**
     * Reconciles all eligible uncertain track outcomes for an execution against the remote destination (Phase 11G).
     *
     * @param executionId execution identifier
     * @param authenticatedUserId ID of the authenticated user
     * @return reconciliation operation summary with itemized outcomes
     */
    com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionReconciliationSummary reconcileExecution(UUID executionId, UUID authenticatedUserId);

    /**
     * Retrieves the audit history of all reconciliation attempts for an execution (Phase 11G).
     *
     * @param executionId execution identifier
     * @param authenticatedUserId ID of the authenticated user
     * @return list of reconciliation audit records
     */
    java.util.List<com.spotifyyoutube.migrator.migration.application.execution.contract.ReconciliationRecordDto> getReconciliationHistory(UUID executionId, UUID authenticatedUserId);

    /**
     * Retrieves the current reconciliation status and eligible uncertain tracks for an execution (Phase 11G).
     *
     * @param executionId execution identifier
     * @param authenticatedUserId ID of the authenticated user
     * @return reconciliation status and eligible tracks
     */
    com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionReconciliationStatusDto getReconciliationStatus(UUID executionId, UUID authenticatedUserId);

    /**
     * Pre-allocates execution intent in ALLOCATING_DESTINATION status before external playlist creation (Phase 13F).
     *
     * @param reviewSessionId unique review session ID
     * @param playlistCreationOperationId stable operation UUID
     * @param authenticatedUserId ID of the authenticated user
     * @return durable execution summary in ALLOCATING_DESTINATION status
     */
    MigrationExecutionSummary initializeExecutionIntent(
            UUID reviewSessionId,
            UUID playlistCreationOperationId,
            UUID authenticatedUserId
    );

    /**
     * Confirms the created destination playlist identity on a pre-allocated execution intent (Phase 13F).
     *
     * @param executionId execution identifier
     * @param destinationPlaylistId created destination playlist ID
     * @param destinationPlaylistUrl destination playlist URL
     * @param nextStatus target lifecycle status (QUEUED or INITIALIZED)
     * @param authenticatedUserId ID of the authenticated user
     * @return updated execution summary
     */
    MigrationExecutionSummary confirmDestinationPlaylist(
            UUID executionId,
            String destinationPlaylistId,
            String destinationPlaylistUrl,
            com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus nextStatus,
            UUID authenticatedUserId
    );

    /**
     * Records a playlist creation failure code and transitions the execution to a target status (Phase 13F).
     *
     * @param executionId execution identifier
     * @param failureCode diagnostic failure code
     * @param nextStatus target lifecycle status (typically RECOVERY_REQUIRED)
     * @param authenticatedUserId ID of the authenticated user
     * @return updated execution summary
     */
    MigrationExecutionSummary recordPlaylistCreationFailure(
            UUID executionId,
            String failureCode,
            com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus nextStatus,
            UUID authenticatedUserId
    );
}
