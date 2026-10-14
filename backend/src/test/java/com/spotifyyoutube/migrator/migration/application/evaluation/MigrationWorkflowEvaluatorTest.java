package com.spotifyyoutube.migrator.migration.application.evaluation;

import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.application.orchestration.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.application.orchestration.TrackMigrationOutcome;
import com.spotifyyoutube.migrator.migration.domain.MigrationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationWorkflowEvaluatorTest {

    private MigrationWorkflowEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new MigrationWorkflowEvaluator();
    }

    @Test
    @DisplayName("Should compute aggregate metrics correctly across multiple playlist migration summaries")
    void shouldComputeAggregateMetricsAcrossPlaylists() {
        // Playlist 1: 3 tracks (2 success, 1 unmatched)
        TrackMigrationOutcome p1t0 = TrackMigrationOutcome.success(
                0, "sp-1", "yt-1", MatchDecision.MATCH, 0.98, OptionalDouble.of(0.99));
        TrackMigrationOutcome p1t1 = TrackMigrationOutcome.success(
                1, "sp-2", "yt-2", MatchDecision.MATCH, 0.85, OptionalDouble.of(0.91));
        TrackMigrationOutcome p1t2 = TrackMigrationOutcome.unmatched(
                2, "sp-3", "No match found");
        MigrationExecutionSummary summary1 = new MigrationExecutionSummary(
                "pl-1", "ext-yt-1", 3, 2, 0, 1, 0, 500L, List.of(p1t0, p1t1, p1t2));

        // Playlist 2: 2 tracks (1 success, 1 review required)
        TrackMigrationOutcome p2t0 = TrackMigrationOutcome.success(
                0, "sp-4", "yt-4", MatchDecision.MATCH, 0.95, OptionalDouble.of(0.97));
        TrackMigrationOutcome p2t1 = TrackMigrationOutcome.reviewRequired(
                1, "sp-5", "yt-5", 0.62, OptionalDouble.of(0.65), "Ambiguous candidates");
        MigrationExecutionSummary summary2 = new MigrationExecutionSummary(
                "pl-2", "ext-yt-2", 2, 1, 0, 0, 1, 500L, List.of(p2t0, p2t1));

        MigrationWorkflowEvaluationReport report = evaluator.evaluateSummaries(
                List.of(summary1, summary2), "Integration Benchmark");

        assertThat(report.totalPlaylistsEvaluated()).isEqualTo(2);
        assertThat(report.totalTracksEvaluated()).isEqualTo(5);
        assertThat(report.successfullyMigratedTracks()).isEqualTo(3);
        assertThat(report.unmatchedTracks()).isEqualTo(1);
        assertThat(report.reviewRequiredTracks()).isEqualTo(1);
        assertThat(report.failedTracks()).isEqualTo(0);
        assertThat(report.duplicateTracksPrevented()).isEqualTo(1);
        assertThat(report.totalDurationMs()).isEqualTo(1000L);

        assertThat(report.migrationSuccessRate()).isEqualTo(0.60);
        assertThat(report.unmatchedRate()).isEqualTo(0.20);
        assertThat(report.reviewRate()).isEqualTo(0.20);
        assertThat(report.failureRate()).isEqualTo(0.0);
        assertThat(report.throughputTracksPerSecond()).isEqualTo(5.0);
        assertThat(report.orderingPreserved()).isTrue();
        assertThat(report.evaluationScope()).isEqualTo("Integration Benchmark");
    }

    @Test
    @DisplayName("Should detect track ordering discrepancy when position does not match index")
    void shouldDetectOrderingDiscrepancy() {
        TrackMigrationOutcome t0 = TrackMigrationOutcome.success(
                1, "sp-1", "yt-1", MatchDecision.MATCH, 0.95, OptionalDouble.of(0.98)); // pos 1 at index 0!
        TrackMigrationOutcome t1 = TrackMigrationOutcome.success(
                0, "sp-2", "yt-2", MatchDecision.MATCH, 0.95, OptionalDouble.of(0.98));
        MigrationExecutionSummary summary = new MigrationExecutionSummary(
                "pl-1", "ext-yt-1", 2, 2, 0, 0, 0, 200L, List.of(t0, t1));

        MigrationWorkflowEvaluationReport report = evaluator.evaluateSummary(summary, "Order Check");

        assertThat(report.orderingPreserved()).isFalse();
    }

    @Test
    @DisplayName("Should handle empty summary list and zero tracks gracefully without division by zero")
    void shouldHandleEmptySummariesGracefully() {
        MigrationWorkflowEvaluationReport report = evaluator.evaluateSummaries(
                List.of(), "Empty Suite");

        assertThat(report.totalPlaylistsEvaluated()).isEqualTo(0);
        assertThat(report.totalTracksEvaluated()).isEqualTo(0);
        assertThat(report.successfullyMigratedTracks()).isEqualTo(0);
        assertThat(report.migrationSuccessRate()).isEqualTo(0.0);
        assertThat(report.unmatchedRate()).isEqualTo(0.0);
        assertThat(report.reviewRate()).isEqualTo(0.0);
        assertThat(report.failureRate()).isEqualTo(0.0);
        assertThat(report.throughputTracksPerSecond()).isEqualTo(0.0);
        assertThat(report.orderingPreserved()).isTrue();
    }

    @Test
    @DisplayName("Should correctly classify track processing failures")
    void shouldClassifyTrackFailures() {
        TrackMigrationOutcome failedTrack = TrackMigrationOutcome.failed(
                0, "sp-1", "YouTube quota exceeded during insertion");
        MigrationExecutionSummary summary = new MigrationExecutionSummary(
                "pl-1", "ext-yt-1", 1, 0, 1, 0, 0, 100L, List.of(failedTrack));

        MigrationWorkflowEvaluationReport report = evaluator.evaluateSummary(summary, "Failure Check");

        assertThat(report.failedTracks()).isEqualTo(1);
        assertThat(report.failureRate()).isEqualTo(1.0);
        assertThat(report.successfullyMigratedTracks()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should format evaluation report to markdown and plain text with all sections")
    void shouldFormatReportToMarkdownAndPlainText() {
        TrackMigrationOutcome t0 = TrackMigrationOutcome.success(
                0, "sp-1", "yt-1", MatchDecision.MATCH, 0.95, OptionalDouble.of(0.98));
        MigrationExecutionSummary summary = new MigrationExecutionSummary(
                "pl-1", "ext-yt-1", 1, 1, 0, 0, 0, 250L, List.of(t0));

        MigrationWorkflowEvaluationReport report = evaluator.evaluateSummary(summary, "Markdown Format Suite");

        String markdown = report.formatToMarkdown();
        assertThat(markdown).contains("# Phase 8H: Playlist Migration Workflow Evaluation & Reliability Report");
        assertThat(markdown).contains("Markdown Format Suite");
        assertThat(markdown).contains("Successfully Migrated");
        assertThat(markdown).contains("100.00%");
        assertThat(markdown).contains("YES (100% Verified)");

        String plainText = report.formatToPlainText();
        assertThat(plainText).contains("MIGRATION EVALUATION REPORT [Markdown Format Suite]");
        assertThat(plainText).contains("Migrated: 1 (100.00%)");
    }

    @Test
    @DisplayName("Should enforce non-null arguments and non-negative counts in report constructor")
    void shouldEnforceConstructorValidations() {
        assertThatThrownBy(() -> evaluator.evaluateSummaries(null, "Scope"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> evaluator.evaluateSummaries(List.of(), null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new MigrationWorkflowEvaluationReport(
                -1, 0, 0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0L, 0.0, true, "Scope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should correctly classify matched candidate that failed destination insertion")
    void shouldClassifyMatchedCandidateFailedInsertion() {
        TrackMigrationOutcome insertionFailedTrack = new TrackMigrationOutcome(
                0, "sp-1", "Song Title", "Artist", "yt-vid-1", "Video Title",
                MatchDecision.MATCH, 0.95, 0.92, MigrationStatus.FAILED, "YouTube API 500 error"
        );
        MigrationExecutionSummary summary = new MigrationExecutionSummary(
                "pl-1", "ext-yt-1", 1, 0, 1, 0, 0, 150L, List.of(insertionFailedTrack));

        MigrationWorkflowEvaluationReport report = evaluator.evaluateSummary(summary, "Insertion Failure Check");

        assertThat(report.failedTracks()).isEqualTo(1);
        assertThat(report.unmatchedTracks()).isEqualTo(0);
        assertThat(report.reviewRequiredTracks()).isEqualTo(0);
        assertThat(report.successfullyMigratedTracks()).isEqualTo(0);
        assertThat(report.failureRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should handle empty playlist with zero tracks and zero duration")
    void shouldHandleEmptyPlaylist() {
        MigrationExecutionSummary emptySummary = new MigrationExecutionSummary(
                "pl-empty", "ext-yt-empty", 0, 0, 0, 0, 0, 0L, List.of());

        MigrationWorkflowEvaluationReport report = evaluator.evaluateSummary(emptySummary, "Empty Playlist Check");

        assertThat(report.totalPlaylistsEvaluated()).isEqualTo(1);
        assertThat(report.totalTracksEvaluated()).isEqualTo(0);
        assertThat(report.successfullyMigratedTracks()).isEqualTo(0);
        assertThat(report.unmatchedTracks()).isEqualTo(0);
        assertThat(report.reviewRequiredTracks()).isEqualTo(0);
        assertThat(report.failedTracks()).isEqualTo(0);
        assertThat(report.duplicateTracksPrevented()).isEqualTo(0);
        assertThat(report.migrationSuccessRate()).isEqualTo(0.0);
        assertThat(report.unmatchedRate()).isEqualTo(0.0);
        assertThat(report.reviewRate()).isEqualTo(0.0);
        assertThat(report.failureRate()).isEqualTo(0.0);
        assertThat(report.throughputTracksPerSecond()).isEqualTo(0.0);
        assertThat(report.orderingPreserved()).isTrue();
    }

    @Test
    @DisplayName("Should handle non-empty playlist with zero duration without division by zero")
    void shouldHandleZeroDurationWithoutDivisionByZero() {
        TrackMigrationOutcome t0 = TrackMigrationOutcome.success(
                0, "sp-1", "yt-1", MatchDecision.MATCH, 0.95, OptionalDouble.of(0.98));
        MigrationExecutionSummary summary = new MigrationExecutionSummary(
                "pl-fast", "ext-yt-fast", 1, 1, 0, 0, 0, 0L, List.of(t0));

        MigrationWorkflowEvaluationReport report = evaluator.evaluateSummary(summary, "Zero Duration Check");

        assertThat(report.totalTracksEvaluated()).isEqualTo(1);
        assertThat(report.successfullyMigratedTracks()).isEqualTo(1);
        assertThat(report.totalDurationMs()).isEqualTo(0L);
        assertThat(report.throughputTracksPerSecond()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Should correctly classify duplicate prevented outcomes separately from genuine failures")
    void shouldCorrectlyClassifyDuplicateOutcomes() {
        TrackMigrationOutcome duplicateOutcome = new TrackMigrationOutcome(
                0, "sp-dup", "Duplicate Song", "Artist", "yt-vid-1", "Video Title",
                MatchDecision.MATCH, 0.95, 0.92, MigrationStatus.FAILED, "Duplicate: candidate 'yt-vid-1' already added to playlist"
        );
        MigrationExecutionSummary summary = new MigrationExecutionSummary(
                "pl-dup", "ext-yt-dup", 1, 0, 1, 1, 0, 120L, List.of(duplicateOutcome));

        MigrationWorkflowEvaluationReport report = evaluator.evaluateSummary(summary, "Duplicate Check");

        assertThat(report.duplicateTracksPrevented()).isEqualTo(1);
        assertThat(report.failedTracks()).isEqualTo(0);
        assertThat(report.unmatchedTracks()).isEqualTo(0);
        assertThat(report.reviewRequiredTracks()).isEqualTo(0);
        assertThat(report.successfullyMigratedTracks()).isEqualTo(0);
        assertThat(report.failureRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Should evaluate summary with empty outcomes using fallback aggregate counts")
    void shouldHandleSummaryWithoutOutcomesUsingFallbackCounts() {
        MigrationExecutionSummary summaryWithoutOutcomes = new MigrationExecutionSummary(
                "pl-no-outcomes", "ext-yt-no", 5, 3, 2, 1, 1, 1000L, List.of());

        MigrationWorkflowEvaluationReport report = evaluator.evaluateSummary(summaryWithoutOutcomes, "Fallback Check");

        assertThat(report.totalTracksEvaluated()).isEqualTo(5);
        assertThat(report.successfullyMigratedTracks()).isEqualTo(3);
        assertThat(report.duplicateTracksPrevented()).isEqualTo(1);
        assertThat(report.reviewRequiredTracks()).isEqualTo(1);
        assertThat(report.failedTracks()).isEqualTo(0);
        assertThat(report.migrationSuccessRate()).isEqualTo(0.60);
    }

    @Test
    @DisplayName("Should correctly classify unexpected processing errors where decision is null")
    void shouldClassifyUnexpectedProcessingErrors() {
        TrackMigrationOutcome unexpectedFailure = TrackMigrationOutcome.failed(
                0, "sp-err", "Track processing failure: Normalization exception occurred"
        );
        MigrationExecutionSummary summary = new MigrationExecutionSummary(
                "pl-err", "ext-yt-err", 1, 0, 1, 0, 0, 100L, List.of(unexpectedFailure));

        MigrationWorkflowEvaluationReport report = evaluator.evaluateSummary(summary, "Unexpected Failure Check");

        assertThat(report.failedTracks()).isEqualTo(1);
        assertThat(report.failureRate()).isEqualTo(1.0);
        assertThat(report.successfullyMigratedTracks()).isEqualTo(0);
        assertThat(report.unmatchedTracks()).isEqualTo(0);
        assertThat(report.reviewRequiredTracks()).isEqualTo(0);
        assertThat(report.duplicateTracksPrevented()).isEqualTo(0);
    }
}
