package com.spotifyyoutube.migrator.migration.review.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Request payload for creating a new migration review session from a source playlist's migration plan.
 *
 * @param sourcePlaylistId source platform playlist identifier (e.g. Spotify playlist ID)
 * @param userId optional authenticated user ID; resolved from security context if omitted
 * @param deduplicate optional flag whether to identify duplicate candidates; defaults to true
 * @param planId optional existing transient plan identifier if created during preview
 */
public record CreateReviewSessionRequest(
        @NotBlank(message = "Source playlist ID cannot be blank")
        @JsonAlias({"spotifyPlaylistId", "playlistId"})
        String sourcePlaylistId,

        UUID userId,

        Boolean deduplicate,

        UUID planId
) {

    public CreateReviewSessionRequest(String sourcePlaylistId) {
        this(sourcePlaylistId, null, null, null);
    }

    public CreateReviewSessionRequest(String sourcePlaylistId, UUID userId) {
        this(sourcePlaylistId, userId, null, null);
    }

    public CreateReviewSessionRequest(String sourcePlaylistId, UUID userId, Boolean deduplicate) {
        this(sourcePlaylistId, userId, deduplicate, null);
    }

    public boolean isDeduplicate() {
        return deduplicate == null || deduplicate;
    }
}
