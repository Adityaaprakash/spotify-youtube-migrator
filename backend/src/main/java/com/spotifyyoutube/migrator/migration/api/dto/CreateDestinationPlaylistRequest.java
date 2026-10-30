package com.spotifyyoutube.migrator.migration.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request payload for creating a destination playlist from an approved review session.
 *
 * @param reviewSessionId unique review session identifier whose decisions are to be executed (required)
 * @param userId optional user identifier (verified against authenticated principal to prevent impersonation)
 * @param sourcePlaylistId optional source playlist identifier for cross-verification
 * @param targetPlaylistTitle optional custom title override for the created playlist
 * @param targetPlaylistDescription optional custom description override for the created playlist
 * @param isPrivate optional privacy override (defaults to true if null)
 */
public record CreateDestinationPlaylistRequest(
        @NotNull(message = "reviewSessionId is required")
        @JsonAlias({"sessionId"})
        UUID reviewSessionId,

        UUID userId,
        String sourcePlaylistId,
        String targetPlaylistTitle,
        String targetPlaylistDescription,
        Boolean isPrivate
) {}
