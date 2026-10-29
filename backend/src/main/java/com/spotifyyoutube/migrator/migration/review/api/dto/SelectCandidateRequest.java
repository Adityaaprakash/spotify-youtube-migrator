package com.spotifyyoutube.migrator.migration.review.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Request payload for manually selecting a replacement candidate for a review track.
 *
 * @param candidate replacement destination candidate details
 * @param notes optional decision explanation or notes
 */
public record SelectCandidateRequest(
        @NotNull(message = "candidate is required")
        @Valid
        CandidateSelectionDto candidate,

        String notes
) {
}
