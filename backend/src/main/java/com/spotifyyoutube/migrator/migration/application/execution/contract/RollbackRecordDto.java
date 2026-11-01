package com.spotifyyoutube.migrator.migration.application.execution.contract;

import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionRollbackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.RollbackRecordStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable domain DTO representing an individual persisted rollback audit record (Phase 11F).
 */
public record RollbackRecordDto(
        UUID id,
        UUID executionId,
        UUID trackRecordId,
        int targetPosition,
        String destinationTrackId,
        String destinationPlaylistItemId,
        RollbackRecordStatus status,
        String errorMessage,
        Instant attemptedAt,
        Instant completedAt
) {

    public static RollbackRecordDto from(ExecutionRollbackRecord record) {
        if (record == null) {
            return null;
        }
        return new RollbackRecordDto(
                record.getId(),
                record.getExecution() != null ? record.getExecution().getId() : null,
                record.getTrackRecord() != null ? record.getTrackRecord().getId() : null,
                record.getTargetPosition(),
                record.getDestinationTrackId(),
                record.getDestinationPlaylistItemId(),
                record.getStatus(),
                record.getErrorMessage(),
                record.getAttemptedAt(),
                record.getCompletedAt()
        );
    }
}
