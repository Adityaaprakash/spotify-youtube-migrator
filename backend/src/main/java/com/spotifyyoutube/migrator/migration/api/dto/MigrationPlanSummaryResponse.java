package com.spotifyyoutube.migrator.migration.api.dto;

import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanSummary;

/**
 * API response representing the reconciled aggregate summary of a migration plan.
 *
 * @param totalTracks total source tracks in the plan
 * @param autoMatchCount tracks eligible for automated migration without review
 * @param reviewRequiredCount ambiguous or borderline tracks held back for manual review
 * @param unmatchedCount tracks with no acceptable candidate found
 * @param duplicateCount tracks where the candidate was already claimed by an earlier track
 * @param failedCount tracks that experienced technical processing errors
 * @param excludedCount tracks explicitly excluded by rule or preference
 * @param autoMatchRate ratio of autoMatchCount to totalTracks
 * @param reviewRate ratio of reviewRequiredCount to totalTracks
 * @param unmatchedRate ratio of unmatchedCount to totalTracks
 * @param duplicateRate ratio of duplicateCount to totalTracks
 * @param failureRate ratio of failedCount to totalTracks
 * @param excludedRate ratio of excludedCount to totalTracks
 */
public record MigrationPlanSummaryResponse(
        int totalTracks,
        int autoMatchCount,
        int reviewRequiredCount,
        int unmatchedCount,
        int duplicateCount,
        int failedCount,
        int excludedCount,
        double autoMatchRate,
        double reviewRate,
        double unmatchedRate,
        double duplicateRate,
        double failureRate,
        double excludedRate
) {

    public static MigrationPlanSummaryResponse from(MigrationPlanSummary summary) {
        if (summary == null) {
            return null;
        }
        return new MigrationPlanSummaryResponse(
                summary.totalTracks(),
                summary.autoMatchCount(),
                summary.reviewRequiredCount(),
                summary.unmatchedCount(),
                summary.duplicateCount(),
                summary.failedCount(),
                summary.excludedCount(),
                summary.getAutoMatchRate(),
                summary.getReviewRate(),
                summary.getUnmatchedRate(),
                summary.getDuplicateRate(),
                summary.getFailureRate(),
                summary.getExcludedRate()
        );
    }
}
