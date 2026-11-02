package com.spotifyyoutube.migrator.migration.api.dto;

import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;

import java.util.List;
import java.util.UUID;

/**
 * REST response representing an execution's current reconciliation requirements and eligible tracks (Phase 11G).
 */
public record ExecutionReconciliationStatusResponse(
        UUID executionId,
        MigrationExecutionStatus executionStatus,
        int totalApprovedTracks,
        int successfulInsertions,
        int failedInsertions,
        int uncertainInsertions,
        int rolledBackInsertions,
        int staleInFlightCount,
        boolean reconciliationRequired,
        List<ExecutionTrackRecordResponse> eligibleTracks
) {}
