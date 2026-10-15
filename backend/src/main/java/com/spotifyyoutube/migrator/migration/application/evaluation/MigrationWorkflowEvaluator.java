package com.spotifyyoutube.migrator.migration.application.evaluation;

import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.application.orchestration.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.application.orchestration.TrackMigrationOutcome;

import java.util.List;
import java.util.Objects;

/**
 * Evaluates migration workflow runs across multiple playlists or benchmark fixtures,
 * synthesizing comprehensive reliability, matching yield, duplicate prevention, and throughput telemetry.
 */
public class MigrationWorkflowEvaluator {

    /**
     * Synthesizes an evaluation report from a list of completed migration execution summaries.
     *
     * @param summaries list of executed migration summaries, must not be null
     * @param evaluationScope descriptive label for evaluation scope (e.g. "Ground-Truth Fixture Suite")
     * @return generated MigrationWorkflowEvaluationReport
     */
    public MigrationWorkflowEvaluationReport evaluateSummaries(
            List<MigrationExecutionSummary> summaries,
            String evaluationScope
    ) {
        Objects.requireNonNull(summaries, "summaries must not be null");
        Objects.requireNonNull(evaluationScope, "evaluationScope must not be null");

        int totalPlaylists = summaries.size();
        int totalTracks = 0;
        int successfulTracks = 0;
        int unmatchedTracks = 0;
        int reviewTracks = 0;
        int failedTracks = 0;
        int duplicatePrevented = 0;
        long totalDurationMs = 0L;
        boolean allOrderingPreserved = true;

        for (MigrationExecutionSummary summary : summaries) {
            totalTracks += summary.totalTracks();
            totalDurationMs += summary.durationMs();

            List<TrackMigrationOutcome> outcomes = summary.trackOutcomes();
            if (outcomes.isEmpty()) {
                successfulTracks += summary.processedTracks();
                duplicatePrevented += summary.duplicateTracks();
                reviewTracks += summary.reviewRequiredTracks();
                int nonFailureUnmigrated = summary.duplicateTracks() + summary.reviewRequiredTracks();
                int remaining = Math.max(0, summary.failedTracks() - nonFailureUnmigrated);
                failedTracks += remaining;
            } else {
                int playlistSuccess = 0;
                int playlistUnmatched = 0;
                int playlistReview = 0;
                int playlistDuplicates = 0;
                int playlistFailures = 0;

                for (int i = 0; i < outcomes.size(); i++) {
                    TrackMigrationOutcome outcome = outcomes.get(i);
                    if (outcome.position() != i) {
                        allOrderingPreserved = false;
                    }
                    if (outcome.isSuccessful()) {
                        playlistSuccess++;
                    } else if (outcome.isDuplicate()) {
                        playlistDuplicates++;
                    } else if (outcome.decision() == MatchDecision.NO_MATCH) {
                        playlistUnmatched++;
                    } else if (outcome.decision() == MatchDecision.REVIEW_REQUIRED) {
                        playlistReview++;
                    } else {
                        playlistFailures++;
                    }
                }

                successfulTracks += playlistSuccess;
                unmatchedTracks += playlistUnmatched;
                reviewTracks += playlistReview;
                duplicatePrevented += Math.max(playlistDuplicates, summary.duplicateTracks());
                failedTracks += playlistFailures;
            }
        }

        double successRate = totalTracks > 0 ? ((double) successfulTracks) / totalTracks : 0.0;
        double unmatchedRate = totalTracks > 0 ? ((double) unmatchedTracks) / totalTracks : 0.0;
        double reviewRate = totalTracks > 0 ? ((double) reviewTracks) / totalTracks : 0.0;
        double failureRate = totalTracks > 0 ? ((double) failedTracks) / totalTracks : 0.0;
        double throughput = totalDurationMs > 0 ? (totalTracks * 1000.0) / totalDurationMs : 0.0;

        return new MigrationWorkflowEvaluationReport(
                totalPlaylists,
                totalTracks,
                successfulTracks,
                unmatchedTracks,
                reviewTracks,
                failedTracks,
                duplicatePrevented,
                successRate,
                unmatchedRate,
                reviewRate,
                failureRate,
                totalDurationMs,
                throughput,
                allOrderingPreserved,
                evaluationScope
        );
    }

    /**
     * Synthesizes an evaluation report from a single migration execution summary.
     */
    public MigrationWorkflowEvaluationReport evaluateSummary(
            MigrationExecutionSummary summary,
            String evaluationScope
    ) {
        Objects.requireNonNull(summary, "summary must not be null");
        return evaluateSummaries(List.of(summary), evaluationScope);
    }
}
