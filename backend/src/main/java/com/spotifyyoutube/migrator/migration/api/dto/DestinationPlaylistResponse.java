package com.spotifyyoutube.migrator.migration.api.dto;

import com.spotifyyoutube.migrator.playlist.domain.Platform;

import java.time.Instant;
import java.util.UUID;

/**
 * Presentation response payload returned after successful destination playlist creation (Phase 11C).
 *
 * @param reviewSessionId unique review session identifier
 * @param planId optional planning identifier from Phase 9
 * @param destinationPlatform target platform enum (e.g. YOUTUBE)
 * @param destinationPlaylistId confirmed platform identifier for the created playlist
 * @param destinationPlaylistUrl web URL to access the destination playlist
 * @param playlistTitle title assigned to the destination playlist
 * @param playlistDescription description assigned to the destination playlist
 * @param isPrivate privacy setting applied to the destination playlist
 * @param approvedTrackCount count of approved tracks ready for insertion in Phase 11D
 * @param status creation status outcome (e.g. "CREATED")
 * @param createdAt timestamp when the playlist was created
 */
public record DestinationPlaylistResponse(
        UUID reviewSessionId,
        UUID planId,
        Platform destinationPlatform,
        String destinationPlaylistId,
        String destinationPlaylistUrl,
        String playlistTitle,
        String playlistDescription,
        boolean isPrivate,
        int approvedTrackCount,
        String status,
        Instant createdAt
) {}
