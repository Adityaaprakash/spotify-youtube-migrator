package com.spotifyyoutube.migrator.migration.review.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Request payload for explicitly excluding a track from migration.
 *
 * @param reason optional reason for excluding the track
 */
public record ExcludeTrackRequest(
        @Size(max = 1000, message = "Exclusion reason cannot exceed 1000 characters")
        String reason
) {

    public ExcludeTrackRequest() {
        this(null);
    }
}
