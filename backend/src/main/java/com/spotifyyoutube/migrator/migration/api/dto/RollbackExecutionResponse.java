package com.spotifyyoutube.migrator.migration.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Presentation response DTO summarizing a rollback operation (Phase 11F).
 *
 * @param executionId execution identifier
 * @param overallStatus overall status of the rollback operation ("SUCCESS", "PARTIAL_SUCCESS", "FAILED", "NO_OP")
 * @param totalRequested total tracks targeted for rollback
 * @param successfullyRolledBack number of tracks confirmed deleted
 * @param failedRollbacks number of tracks where deletion failed
 * @param alreadyRolledBack number of tracks that were already rolled back
 * @param trackOutcomes itemized list of per-track outcomes
 * @param updatedExecution updated execution summary following state reconciliation
 */
public record RollbackExecutionResponse(
        UUID executionId,
        String overallStatus,
        int totalRequested,
        int successfullyRolledBack,
        int failedRollbacks,
        int alreadyRolledBack,
        List<RollbackTrackOutcomeResponse> trackOutcomes,
        MigrationExecutionResponse updatedExecution
) {
}
