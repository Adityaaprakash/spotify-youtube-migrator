package com.spotifyyoutube.migrator.migration.application.execution.contract;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Summary record representing the outcome of a partial or complete rollback operation (Phase 11F).
 *
 * @param executionId execution identifier
 * @param overallStatus overall status of the rollback request (SUCCESS, PARTIAL_SUCCESS, FAILED, NO_OP)
 * @param totalRequested total tracks targeted for rollback
 * @param successfullyRolledBack number of tracks confirmed deleted
 * @param failedRollbacks number of tracks where deletion failed
 * @param alreadyRolledBack number of tracks that were already rolled back prior to request
 * @param trackOutcomes itemized list of per-track outcomes
 * @param updatedExecution updated execution summary following state reconciliation
 */
public record ExecutionRollbackSummary(
        UUID executionId,
        RollbackExecutionStatus overallStatus,
        int totalRequested,
        int successfullyRolledBack,
        int failedRollbacks,
        int alreadyRolledBack,
        List<RollbackTrackOutcomeDto> trackOutcomes,
        MigrationExecutionSummary updatedExecution
) {

    public ExecutionRollbackSummary {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(overallStatus, "overallStatus must not be null");
        trackOutcomes = trackOutcomes != null ? List.copyOf(trackOutcomes) : Collections.emptyList();
    }
}
