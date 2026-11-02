package com.spotifyyoutube.migrator.migration.api.dto;

import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;

import java.util.List;
import java.util.UUID;

/**
 * REST response representing the aggregate outcome of reconciling a migration execution (Phase 11G).
 */
public record ReconcileExecutionResponse(
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
        List<TrackReconciliationOutcomeResponse> trackOutcomes,
        boolean fullyResolved
) {}
