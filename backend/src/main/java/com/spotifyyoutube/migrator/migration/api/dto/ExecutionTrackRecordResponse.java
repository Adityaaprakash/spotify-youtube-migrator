package com.spotifyyoutube.migrator.migration.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Presentation response DTO representing an individual track's durable execution record (Phase 11E).
 *
 * @param id unique record identifier
 * @param targetPosition 0-indexed position targeted in the destination playlist
 * @param sourcePosition original 0-indexed position in the source playlist
 * @param sourceTrackId identifier of the source track
 * @param sourceTitle title of the source track
 * @param sourceArtist artist of the source track
 * @param destinationTrackId destination track/video identifier
 * @param destinationPlaylistItemId provider-assigned playlist item identifier if successfully inserted
 * @param status current status ("PENDING", "ADDING", "ADDED", "FAILED", "UNCERTAIN", "SKIPPED")
 * @param errorMessage sanitized error explanation or timeout message
 * @param attemptCount number of insertion attempts
 * @param startedAt timestamp when insertion attempt began
 * @param completedAt timestamp when insertion settled
 */
public record ExecutionTrackRecordResponse(
        UUID id,
        int targetPosition,
        int sourcePosition,
        String sourceTrackId,
        String sourceTitle,
        String sourceArtist,
        String destinationTrackId,
        String destinationPlaylistItemId,
        String status,
        String errorMessage,
        int attemptCount,
        Instant startedAt,
        Instant completedAt
) {
}
