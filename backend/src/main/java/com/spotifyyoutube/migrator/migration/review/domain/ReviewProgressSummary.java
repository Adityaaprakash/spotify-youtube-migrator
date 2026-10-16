package com.spotifyyoutube.migrator.migration.review.domain;

import java.util.List;
import java.util.Objects;

/**
 * Immutable summary capturing review progress, category counts, unresolved issues,
 * and completion eligibility for a review session.
 *
 * @param totalTracks total tracks in the review session
 * @param awaitingReviewCount tracks currently awaiting user review or decision
 * @param acceptedCount tracks accepted with automatic match candidates
 * @param manuallyCorrectedCount tracks with user-selected replacement candidates
 * @param excludedCount tracks explicitly excluded from migration
 * @param unresolvedConflictCount tracks with active unresolved conflicts (e.g. duplicate destination video IDs)
 * @param remainingRequiredDecisions total number of decisions that must be resolved before completion
 * @param eligibleForCompletion true if all required decisions are resolved and no active conflicts remain
 */
public record ReviewProgressSummary(
        int totalTracks,
        int awaitingReviewCount,
        int acceptedCount,
        int manuallyCorrectedCount,
        int excludedCount,
        int unresolvedConflictCount,
        int remainingRequiredDecisions,
        boolean eligibleForCompletion
) {

    public ReviewProgressSummary {
        if (totalTracks < 0 || awaitingReviewCount < 0 || acceptedCount < 0
                || manuallyCorrectedCount < 0 || excludedCount < 0
                || unresolvedConflictCount < 0 || remainingRequiredDecisions < 0) {
            throw new IllegalArgumentException("Track counts in ReviewProgressSummary must be non-negative");
        }

        int statusTotal = awaitingReviewCount + acceptedCount + manuallyCorrectedCount + excludedCount;
        if (totalTracks != statusTotal) {
            throw new IllegalArgumentException(String.format(
                    "ReviewProgressSummary counts do not reconcile with totalTracks: %d != sum(%d) "
                            + "[awaiting=%d, accepted=%d, manual=%d, excluded=%d]",
                    totalTracks, statusTotal, awaitingReviewCount, acceptedCount, manuallyCorrectedCount, excludedCount
            ));
        }

        if (eligibleForCompletion && remainingRequiredDecisions > 0) {
            throw new IllegalArgumentException(String.format(
                    "Review cannot be eligible for completion when %d required decisions remain unresolved",
                    remainingRequiredDecisions
            ));
        }
    }

    /**
     * Derives an aggregate review progress summary directly from a list of review tracks.
     * Guarantees zero counter drift.
     *
     * @param tracks list of review tracks, or null/empty
     * @return reconciled ReviewProgressSummary
     */
    public static ReviewProgressSummary fromTracks(List<ReviewTrack> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return new ReviewProgressSummary(0, 0, 0, 0, 0, 0, 0, true);
        }

        int awaiting = 0;
        int accepted = 0;
        int manuallyCorrected = 0;
        int excluded = 0;
        int unresolvedConflicts = 0;

        for (ReviewTrack track : tracks) {
            Objects.requireNonNull(track, "Track entry in review tracks list must not be null");

            switch (track.getReviewStatus()) {
                case AWAITING_REVIEW -> awaiting++;
                case ACCEPTED -> accepted++;
                case USER_SELECTED -> manuallyCorrected++;
                case EXCLUDED -> excluded++;
            }

            // Excluded tracks cannot have active duplicate conflicts blocking migration
            if (track.hasConflict() && !track.isExcluded()) {
                unresolvedConflicts++;
            }
        }

        // Remaining decisions = tracks awaiting review + approved tracks that have an unresolved conflict
        // If a track is awaiting review AND has a conflict, it is already counted in awaiting.
        int remainingDecisions = awaiting;
        for (ReviewTrack track : tracks) {
            if (track.getReviewStatus() != ReviewTrackStatus.AWAITING_REVIEW
                    && !track.isExcluded()
                    && track.hasConflict()) {
                remainingDecisions++;
            }
        }

        boolean eligible = remainingDecisions == 0;

        return new ReviewProgressSummary(
                tracks.size(),
                awaiting,
                accepted,
                manuallyCorrected,
                excluded,
                unresolvedConflicts,
                remainingDecisions,
                eligible
        );
    }

    public static ReviewProgressSummary empty() {
        return new ReviewProgressSummary(0, 0, 0, 0, 0, 0, 0, true);
    }
}
