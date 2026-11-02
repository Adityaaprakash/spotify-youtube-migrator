package com.spotifyyoutube.migrator.migration.application.execution.contract;

import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.ReconciliationOutcome;
import com.spotifyyoutube.migrator.migration.execution.domain.ReconciliationType;

import java.time.Instant;

/**
 * Outcome of reconciling an individual track record (Phase 11G).
 */
public record TrackReconciliationOutcomeDto(
        int targetPosition,
        String sourceTrackId,
        String destinationTrackId,
        String destinationPlaylistItemId,
        ReconciliationType reconciliationType,
        ExecutionTrackStatus previousStatus,
        ExecutionTrackStatus resolvedStatus,
        ReconciliationOutcome outcome,
        String evidenceSummary,
        String errorMessage,
        Instant timestamp
) {}
