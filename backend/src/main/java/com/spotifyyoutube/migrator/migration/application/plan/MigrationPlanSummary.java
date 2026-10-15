package com.spotifyyoutube.migrator.migration.application.plan;

import java.util.List;
import java.util.Objects;

/**
 * Immutable summary capturing aggregate track classifications and counts for a migration plan.
 * Enforces strict reconciliation invariant:
 * totalTracks == autoMatchCount + reviewRequiredCount + unmatchedCount + duplicateCount + failedCount + excludedCount
 *
 * @param totalTracks total source tracks in the plan
 * @param autoMatchCount tracks eligible for automated insertion without review
 * @param reviewRequiredCount ambiguous or borderline tracks held back for manual review
 * @param unmatchedCount tracks with no acceptable destination candidate
 * @param duplicateCount tracks where candidate was already claimed by an earlier track
 * @param failedCount tracks with technical or diagnostic processing failures
 * @param excludedCount tracks explicitly excluded by rule or user preference
 */
public record MigrationPlanSummary(
        int totalTracks,
        int autoMatchCount,
        int reviewRequiredCount,
        int unmatchedCount,
        int duplicateCount,
        int failedCount,
        int excludedCount
) {

    public MigrationPlanSummary {
        if (totalTracks < 0 || autoMatchCount < 0 || reviewRequiredCount < 0
                || unmatchedCount < 0 || duplicateCount < 0 || failedCount < 0 || excludedCount < 0) {
            throw new IllegalArgumentException("Track counts must be non-negative");
        }

        int reconciledTotal = autoMatchCount + reviewRequiredCount + unmatchedCount
                + duplicateCount + failedCount + excludedCount;
        if (totalTracks != reconciledTotal) {
            throw new IllegalArgumentException(String.format(
                    "Summary counts do not reconcile: totalTracks (%d) != sum of categories (%d) "
                            + "[autoMatch=%d, reviewRequired=%d, unmatched=%d, duplicate=%d, failed=%d, excluded=%d]",
                    totalTracks, reconciledTotal, autoMatchCount, reviewRequiredCount,
                    unmatchedCount, duplicateCount, failedCount, excludedCount
            ));
        }
    }

    /**
     * Derives an aggregate summary directly from a list of plan track entries.
     * Guarantees that the summary satisfies the reconciliation invariant.
     *
     * @param tracks list of planned tracks, or null/empty
     * @return reconciled MigrationPlanSummary
     */
    public static MigrationPlanSummary fromTracks(List<MigrationPlanTrack> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return new MigrationPlanSummary(0, 0, 0, 0, 0, 0, 0);
        }

        int autoMatch = 0;
        int reviewRequired = 0;
        int unmatched = 0;
        int duplicate = 0;
        int failed = 0;
        int excluded = 0;

        for (MigrationPlanTrack track : tracks) {
            Objects.requireNonNull(track, "track entry in tracks list must not be null");
            switch (track.classification()) {
                case AUTO_MATCH -> autoMatch++;
                case REVIEW_REQUIRED -> reviewRequired++;
                case UNMATCHED -> unmatched++;
                case DUPLICATE -> duplicate++;
                case FAILED -> failed++;
                case EXCLUDED -> excluded++;
            }
        }

        return new MigrationPlanSummary(
                tracks.size(),
                autoMatch,
                reviewRequired,
                unmatched,
                duplicate,
                failed,
                excluded
        );
    }

    public static MigrationPlanSummary empty() {
        return new MigrationPlanSummary(0, 0, 0, 0, 0, 0, 0);
    }

    public double getAutoMatchRate() {
        return totalTracks > 0 ? ((double) autoMatchCount) / totalTracks : 0.0;
    }

    public double getReviewRate() {
        return totalTracks > 0 ? ((double) reviewRequiredCount) / totalTracks : 0.0;
    }

    public double getUnmatchedRate() {
        return totalTracks > 0 ? ((double) unmatchedCount) / totalTracks : 0.0;
    }

    public double getDuplicateRate() {
        return totalTracks > 0 ? ((double) duplicateCount) / totalTracks : 0.0;
    }

    public double getFailureRate() {
        return totalTracks > 0 ? ((double) failedCount) / totalTracks : 0.0;
    }

    public double getExcludedRate() {
        return totalTracks > 0 ? ((double) excludedCount) / totalTracks : 0.0;
    }

    public boolean hasIssues() {
        return reviewRequiredCount > 0 || unmatchedCount > 0 || failedCount > 0;
    }

    public boolean isActionable() {
        return autoMatchCount > 0 || reviewRequiredCount > 0;
    }

    public boolean isEmpty() {
        return totalTracks == 0;
    }
}
