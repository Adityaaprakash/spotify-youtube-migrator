package com.spotifyyoutube.migrator.migration.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;

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

        String sourcePlaylistId,
        String targetPlaylistTitle,
        String targetPlaylistDescription,
        Boolean isPrivate,
        UUID userId
) {
}
