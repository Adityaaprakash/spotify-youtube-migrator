package com.spotifyyoutube.migrator.migration.api.dto;

/**
 * API presentation DTO representing the outcome of inserting an individual track into the destination playlist (Phase 11D).
 *
 * @param targetPosition 0-indexed position targeted in the destination playlist
 * @param sourcePosition original 0-indexed position in the source playlist
 * @param sourceTrackId identifier of the source track
 * @param sourceTitle title of the source track
 * @param sourceArtist artist of the source track
 * @param destinationTrackId destination track/video identifier
 * @param destinationPlaylistItemId provider-assigned playlist item identifier if successfully inserted
 * @param status status outcome ("SUCCESS", "FAILED", "UNCERTAIN", "SKIPPED")
 * @param message human-readable status explanation or sanitized error message
 */
public record TrackInsertionOutcomeResponse(
        int targetPosition,
        int sourcePosition,
        String sourceTrackId,
        String sourceTitle,
        String sourceArtist,
        String destinationTrackId,
        String destinationPlaylistItemId,
        String status,
        String message
) {
}
