package com.spotifyyoutube.migrator.migration.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
        @Size(max = 100, message = "destinationPlaylistId must not exceed 100 characters")
        @JsonAlias({"playlistId"})
        String destinationPlaylistId,

        @Size(max = 2048, message = "destinationPlaylistUrl must not exceed 2048 characters")
        String destinationPlaylistUrl,

        UUID userId
) {
}
