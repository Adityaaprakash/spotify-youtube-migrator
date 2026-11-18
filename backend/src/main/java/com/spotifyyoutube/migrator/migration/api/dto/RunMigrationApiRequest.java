package com.spotifyyoutube.migrator.migration.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request payload for orchestrating an approved playlist migration end-to-end (Phase 11H).
 *
 * @param reviewSessionId unique review session identifier whose decisions are to be executed (required)
 * @param userId optional user identifier (verified against authenticated principal to prevent impersonation)
 * @param sourcePlaylistId optional source playlist identifier for cross-verification
 * @param targetPlaylistTitle optional custom title override for the created playlist
 * @param targetPlaylistDescription optional custom description override for the created playlist
 * @param isPrivate optional privacy override (defaults to true if null)
 */
public record RunMigrationApiRequest(
        @NotNull(message = "reviewSessionId is required")
        @JsonAlias({"sessionId"})
        UUID reviewSessionId,

        UUID userId,

        @Size(max = 100, message = "sourcePlaylistId must not exceed 100 characters")
        String sourcePlaylistId,

        @Size(max = 255, message = "targetPlaylistTitle must not exceed 255 characters")
        String targetPlaylistTitle,

        @Size(max = 2000, message = "targetPlaylistDescription must not exceed 2000 characters")
        String targetPlaylistDescription,

        Boolean isPrivate
) {

    /**
     * Convenience constructor accepting only the required reviewSessionId.
     *
     * @param reviewSessionId unique review session identifier
     */
    public RunMigrationApiRequest(UUID reviewSessionId) {
        this(reviewSessionId, null, null, null, null, null);
    }
}

