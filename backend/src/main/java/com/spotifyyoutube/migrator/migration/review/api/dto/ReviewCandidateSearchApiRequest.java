package com.spotifyyoutube.migrator.migration.review.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Request payload for searching destination candidates for a review track.
 *
 * @param query optional explicit search query string (max 500 characters); if null or blank,
 *              the system derives queries from the track's normalized metadata
 * @param limit optional maximum number of candidate results requested (between 1 and 50)
 */
public record ReviewCandidateSearchApiRequest(
        @Size(max = 500, message = "Search query cannot exceed 500 characters")
        String query,

        @Min(value = 1, message = "Limit must be at least 1")
        @Max(value = 50, message = "Limit cannot exceed 50")
        Integer limit
) {

    public ReviewCandidateSearchApiRequest() {
        this(null, null);
    }
}
