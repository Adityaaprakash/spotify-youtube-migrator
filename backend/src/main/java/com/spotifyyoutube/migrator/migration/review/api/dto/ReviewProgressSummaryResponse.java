package com.spotifyyoutube.migrator.migration.review.api.dto;

import com.spotifyyoutube.migrator.migration.review.domain.ReviewProgressSummary;

/**
 * API response representing the real-time progress, counts, and completion eligibility of a review session.
 *
 * @param totalTracks total count of source tracks in the review session
 * @param awaitingReviewCount tracks currently awaiting user review or decision
 * @param acceptedCount tracks accepted with automatic match candidates
 * @param manuallyCorrectedCount tracks with user-selected replacement candidates
 * @param excludedCount tracks explicitly excluded from migration
 * @param unresolvedConflictCount tracks with active unresolved duplicate conflicts
 * @param remainingRequiredDecisions total decisions remaining before session can be completed
 * @param eligibleForCompletion true if all decisions and conflicts are resolved
 */
public record ReviewProgressSummaryResponse(
        int totalTracks,
        int awaitingReviewCount,
        int acceptedCount,
        int manuallyCorrectedCount,
        int excludedCount,
        int unresolvedConflictCount,
        int remainingRequiredDecisions,
        boolean eligibleForCompletion
) {

    public static ReviewProgressSummaryResponse from(ReviewProgressSummary summary) {
        if (summary == null) {
            return null;
        }
        return new ReviewProgressSummaryResponse(
                summary.totalTracks(),
                summary.awaitingReviewCount(),
                summary.acceptedCount(),
                summary.manuallyCorrectedCount(),
                summary.excludedCount(),
                summary.unresolvedConflictCount(),
                summary.remainingRequiredDecisions(),
                summary.eligibleForCompletion()
        );
    }
}
