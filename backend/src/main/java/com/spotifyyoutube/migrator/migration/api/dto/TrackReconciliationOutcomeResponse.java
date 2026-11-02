package com.spotifyyoutube.migrator.migration.api.dto;

import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.ReconciliationOutcome;
import com.spotifyyoutube.migrator.migration.execution.domain.ReconciliationType;

import java.time.Instant;

/**
 * REST response item for an individual track's reconciliation outcome (Phase 11G).
 */
public record TrackReconciliationOutcomeResponse(
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
