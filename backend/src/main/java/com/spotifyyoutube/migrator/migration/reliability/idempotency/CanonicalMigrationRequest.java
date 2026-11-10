package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable canonical request representation for deterministic idempotency fingerprinting (Phase 13E).
 *
 * @param userId authenticated user ID
 * @param sourcePlaylistId source playlist identifier
 * @param targetPlaylistName destination playlist title/name
 * @param trackHashes list of track identifiers or hashes (order-insensitive; duplicates preserved)
 */
public record CanonicalMigrationRequest(
        UUID userId,
        String sourcePlaylistId,
        String targetPlaylistName,
        List<String> trackHashes
) {
    public CanonicalMigrationRequest {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(sourcePlaylistId, "sourcePlaylistId must not be null");
        if (sourcePlaylistId.isBlank()) {
            throw new IllegalArgumentException("sourcePlaylistId must not be blank");
        }
        Objects.requireNonNull(targetPlaylistName, "targetPlaylistName must not be null");
        if (targetPlaylistName.isBlank()) {
            throw new IllegalArgumentException("targetPlaylistName must not be blank");
        }
        trackHashes = trackHashes != null ? List.copyOf(trackHashes) : List.of();
    }
}
