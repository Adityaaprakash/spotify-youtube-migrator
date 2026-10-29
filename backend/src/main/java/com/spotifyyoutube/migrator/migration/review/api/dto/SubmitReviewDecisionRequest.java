package com.spotifyyoutube.migrator.migration.review.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Request payload for submitting a review decision for a track.
 *
 * @param decisionType the review decision action (ACCEPT, SELECT_CANDIDATE, EXCLUDE)
 * @param candidate candidate details required when decisionType is SELECT_CANDIDATE
 * @param notes optional reviewer notes or explanation
 */
public record SubmitReviewDecisionRequest(
        @NotNull(message = "decisionType is required")
        ReviewDecisionType decisionType,

        @Valid
        CandidateSelectionDto candidate,

        String notes
) {

    public SubmitReviewDecisionRequest {
        if (decisionType == ReviewDecisionType.SELECT_CANDIDATE && candidate == null) {
            throw new IllegalArgumentException("candidate is required when decisionType is SELECT_CANDIDATE");
        }
    }

    public static SubmitReviewDecisionRequest accept() {
        return new SubmitReviewDecisionRequest(ReviewDecisionType.ACCEPT, null, null);
    }

    public static SubmitReviewDecisionRequest select(CandidateSelectionDto candidate, String notes) {
        return new SubmitReviewDecisionRequest(ReviewDecisionType.SELECT_CANDIDATE, candidate, notes);
    }

    public static SubmitReviewDecisionRequest exclude(String reason) {
        return new SubmitReviewDecisionRequest(ReviewDecisionType.EXCLUDE, null, reason);
    }
}
