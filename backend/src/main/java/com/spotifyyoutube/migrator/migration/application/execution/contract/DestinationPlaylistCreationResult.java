package com.spotifyyoutube.migrator.migration.application.execution.contract;

import com.spotifyyoutube.migrator.playlist.domain.Platform;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable domain contract representing the successful outcome of creating a destination playlist.
 * <p>
 * Contains the confirmed destination playlist identity and metadata required for Phase 11D track insertion.
 * Guarantees that the destination playlist identity is non-null, non-blank, and associated with a positive
 * count of approved tracks.
 *
 * @param reviewSessionId unique review session identifier whose decisions were used
 * @param planId unique planning session identifier from Phase 9 snapshot, if correlated
 * @param userId unique identifier of the authenticated user
 * @param destinationPlatform target platform enum (e.g. Platform.YOUTUBE)
 * @param destinationPlaylistId confirmed platform identifier for the created playlist
 * @param destinationPlaylistUrl web URL to access the destination playlist
 * @param playlistTitle effective title assigned to the destination playlist
 * @param playlistDescription effective description assigned to the destination playlist
 * @param isPrivate privacy setting applied to the destination playlist
 * @param approvedTrackCount count of approved tracks awaiting insertion in Phase 11D
 * @param createdAt UTC timestamp when the playlist was created
 */
public record DestinationPlaylistCreationResult(
        UUID reviewSessionId,
        UUID planId,
        UUID userId,
        Platform destinationPlatform,
        String destinationPlaylistId,
        String destinationPlaylistUrl,
        String playlistTitle,
        String playlistDescription,
        boolean isPrivate,
        int approvedTrackCount,
        Instant createdAt
) {

    public DestinationPlaylistCreationResult {
        Objects.requireNonNull(reviewSessionId, "reviewSessionId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(destinationPlatform, "destinationPlatform must not be null");
        Objects.requireNonNull(destinationPlaylistId, "destinationPlaylistId must not be null");
        if (destinationPlaylistId.isBlank()) {
            throw new IllegalArgumentException("destinationPlaylistId must not be blank");
        }
        Objects.requireNonNull(playlistTitle, "playlistTitle must not be null");
        if (playlistTitle.isBlank()) {
            throw new IllegalArgumentException("playlistTitle must not be blank");
        }
        if (approvedTrackCount <= 0) {
            throw new IllegalArgumentException("approvedTrackCount must be positive: " + approvedTrackCount);
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public Optional<UUID> getPlanId() {
        return Optional.ofNullable(planId);
    }

    public Optional<String> getDestinationPlaylistUrl() {
        return Optional.ofNullable(destinationPlaylistUrl);
    }

    public Optional<String> getPlaylistDescription() {
        return Optional.ofNullable(playlistDescription);
    }
}
