package com.spotifyyoutube.migrator.migration.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Presentation request DTO to initialize a durable migration execution record (Phase 11E).
 *
 * @param reviewSessionId unique identifier of the completed review session
 * @param destinationPlaylistId platform identifier for the created destination playlist
 * @param destinationPlaylistUrl optional web URL for the playlist
 * @param userId optional user ID for explicit validation
 */
public record InitializeExecutionRequest(
        @NotNull(message = "reviewSessionId must not be null")
        @JsonAlias({"sessionId"})
        UUID reviewSessionId,

        @NotBlank(message = "destinationPlaylistId must not be blank")
        @JsonAlias({"playlistId"})
        String destinationPlaylistId,

        String destinationPlaylistUrl,

        UUID userId
) {
}
