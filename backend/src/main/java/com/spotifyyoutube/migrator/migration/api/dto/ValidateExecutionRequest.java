package com.spotifyyoutube.migrator.migration.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request payload for validating an approved migration execution.
 *
 * @param reviewSessionId unique review session identifier whose decisions are to be executed
 * @param sourcePlaylistId optional source playlist identifier for cross-verification
 * @param targetPlaylistTitle optional custom title for the destination playlist
 * @param targetPlaylistDescription optional custom description for the destination playlist
 * @param isPrivate privacy setting for the destination playlist (defaults to true if null)
 * @param userId optional user ID for anti-impersonation verification against authenticated principal
 */
public record ValidateExecutionRequest(
        @NotNull(message = "reviewSessionId must not be null")
        @JsonAlias({"sessionId"})
        UUID reviewSessionId,

        @Size(max = 100, message = "sourcePlaylistId must not exceed 100 characters")
        String sourcePlaylistId,

        @Size(max = 255, message = "targetPlaylistTitle must not exceed 255 characters")
        String targetPlaylistTitle,

        @Size(max = 2000, message = "targetPlaylistDescription must not exceed 2000 characters")
        String targetPlaylistDescription,

        Boolean isPrivate,
        UUID userId
) {
}
