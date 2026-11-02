package com.spotifyyoutube.migrator.migration.application.execution.contract;

import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;

import java.util.List;
import java.util.UUID;

/**
 * Status of an execution's unresolved uncertain outcomes and eligible tracks (Phase 11G).
 */
public record ExecutionReconciliationStatusDto(
        UUID executionId,
        MigrationExecutionStatus executionStatus,
        int totalApprovedTracks,
        int successfulInsertions,
        int failedInsertions,
        int uncertainInsertions,
        int rolledBackInsertions,
        int staleInFlightCount,
        boolean reconciliationRequired,
        List<ExecutionTrackRecordDto> eligibleTracks
) {}
