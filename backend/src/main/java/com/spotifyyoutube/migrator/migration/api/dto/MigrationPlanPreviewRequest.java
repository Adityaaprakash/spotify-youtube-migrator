package com.spotifyyoutube.migrator.migration.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Request payload for generating a transient migration plan preview.
 *
 * @param spotifyPlaylistId source Spotify playlist identifier (e.g. Spotify playlist ID)
 * @param userId optional authenticated user ID; resolved from security context if omitted
 * @param deduplicate optional flag whether to identify duplicate candidates; defaults to true
 */
public record MigrationPlanPreviewRequest(
        @NotBlank(message = "Spotify playlist ID cannot be blank")
        @JsonAlias({"sourcePlaylistId", "playlistId"})
        String spotifyPlaylistId,

        UUID userId,

        Boolean deduplicate
) {

    public MigrationPlanPreviewRequest(String spotifyPlaylistId) {
        this(spotifyPlaylistId, null, null);
    }

    public MigrationPlanPreviewRequest(String spotifyPlaylistId, UUID userId) {
        this(spotifyPlaylistId, userId, null);
    }

    public boolean isDeduplicate() {
        return deduplicate == null || deduplicate;
    }
}
