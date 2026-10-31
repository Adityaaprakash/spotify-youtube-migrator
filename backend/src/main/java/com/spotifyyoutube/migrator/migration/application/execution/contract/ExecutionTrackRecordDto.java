package com.spotifyyoutube.migrator.migration.application.execution.contract;

import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable domain DTO representing an individual track's durable execution record (Phase 11E).
 */
public record ExecutionTrackRecordDto(
        UUID id,
        int targetPosition,
        int sourcePosition,
        String sourceTrackId,
        String sourceTitle,
        String sourceArtist,
        String destinationTrackId,
        String destinationPlaylistItemId,
        ExecutionTrackStatus status,
        String errorMessage,
        int attemptCount,
        Instant startedAt,
        Instant completedAt
) {
    public static ExecutionTrackRecordDto from(ExecutionTrackRecord record) {
        if (record == null) {
            return null;
        }
        return new ExecutionTrackRecordDto(
                record.getId(),
                record.getTargetPosition(),
                record.getSourcePosition(),
                record.getSourceTrackId(),
                record.getSourceTitle(),
                record.getSourceArtist(),
                record.getDestinationTrackId(),
                record.getDestinationPlaylistItemId(),
                record.getStatus(),
                record.getErrorMessage(),
                record.getAttemptCount(),
                record.getStartedAt(),
                record.getCompletedAt()
        );
    }
}
