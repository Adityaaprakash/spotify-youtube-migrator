package com.spotifyyoutube.migrator.migration.application.execution.contract;

import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable domain DTO representing an individual track's durable execution record (Phases 11E & 14B).
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
        Instant completedAt,
        String destinationTitle,
        String destinationChannel,
        Long destinationDurationMs,
        String decisionSource,
        Double matchScore,
        String matchDecision
) {

    /**
     * Backward-compatible 13-parameter constructor (Phase 11E).
     */
    public ExecutionTrackRecordDto(
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
        this(
                id,
                targetPosition,
                sourcePosition,
                sourceTrackId,
                sourceTitle,
                sourceArtist,
                destinationTrackId,
                destinationPlaylistItemId,
                status,
                errorMessage,
                attemptCount,
                startedAt,
                completedAt,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

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
                record.getCompletedAt(),
                record.getDestinationTitle(),
                record.getDestinationChannel(),
                record.getDestinationDurationMs(),
                record.getDecisionSource(),
                record.getMatchScore(),
                record.getMatchDecision()
        );
    }
}
