package com.spotifyyoutube.migrator.migration.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request payload for inserting approved tracks into a created destination playlist (Phase 11D).
 *
 * @param reviewSessionId unique review session identifier whose approved decisions are to be executed
 * @param destinationPlaylistId platform identifier of the created destination playlist
 * @param userId optional user identifier (verified against authenticated principal to prevent impersonation)
 */
public record InsertTracksRequest(
        @NotNull(message = "reviewSessionId is required")
        @JsonAlias({"sessionId"})
        UUID reviewSessionId,

        @NotBlank(message = "destinationPlaylistId is required")
        @JsonAlias({"playlistId"})
        String destinationPlaylistId,

        UUID userId
) {}
