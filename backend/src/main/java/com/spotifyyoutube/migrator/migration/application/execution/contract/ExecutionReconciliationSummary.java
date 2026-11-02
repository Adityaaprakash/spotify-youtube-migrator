package com.spotifyyoutube.migrator.migration.application.execution.contract;

import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;

import java.util.List;
import java.util.UUID;

/**
 * Summary of reconciling an execution's uncertain outcomes against remote destination state (Phase 11G).
 */
public record ExecutionReconciliationSummary(
        UUID executionId,
        MigrationExecutionStatus previousExecutionStatus,
        MigrationExecutionStatus resolvedExecutionStatus,
        int totalUncertainTracks,
        int reconciledTracksCount,
        int confirmedInsertedCount,
        int confirmedNotInsertedCount,
        int confirmedDeletedCount,
        int confirmedPresentCount,
        int ambiguousCount,
        int inspectionFailedCount,
        List<TrackReconciliationOutcomeDto> trackOutcomes,
        boolean fullyResolved
) {}
