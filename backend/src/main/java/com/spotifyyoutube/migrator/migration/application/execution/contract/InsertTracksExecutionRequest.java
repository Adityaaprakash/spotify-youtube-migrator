package com.spotifyyoutube.migrator.migration.application.execution.contract;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable application contract representing a request to insert approved tracks into a destination playlist.
 *
 * @param reviewSessionId unique review session identifier whose decisions are to be executed
 * @param destinationPlaylistId platform identifier of the destination playlist
 * @param userId optional requesting user identifier for anti-impersonation verification
 */
public record InsertTracksExecutionRequest(
        UUID reviewSessionId,
        String destinationPlaylistId,
        UUID userId
) {

    public InsertTracksExecutionRequest {
        Objects.requireNonNull(reviewSessionId, "reviewSessionId must not be null");
        Objects.requireNonNull(destinationPlaylistId, "destinationPlaylistId must not be null");
        if (destinationPlaylistId.isBlank()) {
            throw new IllegalArgumentException("destinationPlaylistId must not be blank");
        }
    }

    public static InsertTracksExecutionRequest of(UUID reviewSessionId, String destinationPlaylistId) {
        return new InsertTracksExecutionRequest(reviewSessionId, destinationPlaylistId, null);
    }

    public static InsertTracksExecutionRequest of(UUID reviewSessionId, String destinationPlaylistId, UUID userId) {
        return new InsertTracksExecutionRequest(reviewSessionId, destinationPlaylistId, userId);
    }

    public Optional<UUID> getUserId() {
        return Optional.ofNullable(userId);
    }
}
