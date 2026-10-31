package com.spotifyyoutube.migrator.migration.application.execution.contract;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain request to initialize a durable migration execution record (Phase 11E).
 */
public record InitializeExecutionRequest(
        UUID reviewSessionId,
        String destinationPlaylistId,
        String destinationPlaylistUrl,
        UUID userId
) {

    public InitializeExecutionRequest {
        Objects.requireNonNull(reviewSessionId, "reviewSessionId must not be null");
        Objects.requireNonNull(destinationPlaylistId, "destinationPlaylistId must not be null");
        if (destinationPlaylistId.isBlank()) {
            throw new IllegalArgumentException("destinationPlaylistId must not be blank");
        }
    }

    public Optional<UUID> getUserId() {
        return Optional.ofNullable(userId);
    }

    public Optional<String> getDestinationPlaylistUrl() {
        return Optional.ofNullable(destinationPlaylistUrl);
    }

    public static InitializeExecutionRequest of(UUID reviewSessionId, String destinationPlaylistId, UUID userId) {
        return new InitializeExecutionRequest(reviewSessionId, destinationPlaylistId, null, userId);
    }
}
