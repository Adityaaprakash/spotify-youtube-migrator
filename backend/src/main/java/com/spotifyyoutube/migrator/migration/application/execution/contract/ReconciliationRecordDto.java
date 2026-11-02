package com.spotifyyoutube.migrator.migration.application.execution.contract;

import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionReconciliationRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.ReconciliationOutcome;
import com.spotifyyoutube.migrator.migration.execution.domain.ReconciliationType;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit record DTO for a historical reconciliation attempt (Phase 11G).
 */
public record ReconciliationRecordDto(
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
) {
    public static ReconciliationRecordDto from(ExecutionReconciliationRecord record) {
        if (record == null) {
            return null;
        }
        return new ReconciliationRecordDto(
                record.getId(),
                record.getExecution() != null ? record.getExecution().getId() : null,
                record.getTrackRecord() != null ? record.getTrackRecord().getId() : null,
                record.getTrackRecord() != null ? record.getTrackRecord().getTargetPosition() : -1,
                record.getReconciliationType(),
                record.getPreviousTrackStatus(),
                record.getResolvedTrackStatus(),
                record.getReconciliationOutcome(),
                record.getDestinationPlaylistId(),
                record.getDestinationPlaylistItemId(),
                record.getDestinationTrackId(),
                record.getEvidenceSummary(),
                record.getEvidenceTimestamp(),
                record.getInspectionStartedAt(),
                record.getInspectionCompletedAt(),
                record.getFailureCode(),
                record.getSanitizedErrorMessage(),
                record.getCreatedAt()
        );
    }
}
