package com.spotifyyoutube.migrator.migration.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API response representing the validated approved migration context.
 *
 * @param reviewSessionId unique review session identifier
 * @param planId unique planning session identifier from Phase 9
 * @param sourcePlaylistId source platform playlist identifier
 * @param sourcePlaylistTitle source playlist title
 * @param targetPlaylistTitle destination playlist title
 * @param targetPlaylistDescription destination playlist description
 * @param isPrivate destination playlist privacy setting
 * @param totalSourceTracks total tracks evaluated in the source playlist
 * @param approvedTrackCount count of approved tracks
 * @param excludedTrackCount count of excluded tracks
 * @param validatedAt timestamp when validation succeeded
 * @param approvedTracks itemized list of approved tracks in destination order
 */
public record ApprovedMigrationContextResponse(
        UUID reviewSessionId,
        UUID planId,
        String sourcePlaylistId,
        String sourcePlaylistTitle,
        String targetPlaylistTitle,
        String targetPlaylistDescription,
        boolean isPrivate,
        int totalSourceTracks,
        int approvedTrackCount,
        int excludedTrackCount,
        Instant validatedAt,
        List<ApprovedTrackResponse> approvedTracks
) {
}
