package com.spotifyyoutube.migrator.migration.api.dto;

import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.ReconciliationOutcome;
import com.spotifyyoutube.migrator.migration.execution.domain.ReconciliationType;

import java.time.Instant;
import java.util.UUID;

/**
 * REST response representing a historical reconciliation audit entry (Phase 11G).
 */
public record ReconciliationRecordResponse(
        UUID id,
        UUID executionId,
        UUID trackRecordId,
        int targetPosition,
        ReconciliationType reconciliationType,
        ExecutionTrackStatus previousTrackStatus,
        ExecutionTrackStatus resolvedTrackStatus,
        ReconciliationOutcome reconciliationOutcome,
        String destinationPlaylistId,
        String destinationPlaylistItemId,
        String destinationTrackId,
        String evidenceSummary,
        Instant evidenceTimestamp,
        Instant inspectionStartedAt,
        Instant inspectionCompletedAt,
        String failureCode,
        String sanitizedErrorMessage,
        Instant createdAt
) {}
