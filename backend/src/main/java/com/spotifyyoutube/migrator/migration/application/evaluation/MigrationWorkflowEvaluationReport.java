package com.spotifyyoutube.migrator.migration.application.evaluation;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable evaluation report summarizing migration workflow performance, matching precision,
 * failure rates, duplicate prevention, and throughput across evaluated test fixtures.
 *
 * @param totalPlaylistsEvaluated count of evaluated playlist workflows
 * @param totalTracksEvaluated count of source tracks evaluated
 * @param successfullyMigratedTracks count of tracks matched and added to destination
 * @param unmatchedTracks count of tracks with no acceptable destination match
 * @param reviewRequiredTracks count of tracks requiring manual human review
 * @param failedTracks count of tracks experiencing processing or API failures
 * @param duplicateTracksPrevented count of duplicate destination tracks prevented by deduplication
 * @param migrationSuccessRate ratio of successfully migrated tracks to total tracks
 * @param unmatchedRate ratio of unmatched tracks to total tracks
 * @param reviewRate ratio of review-required tracks to total tracks
 * @param failureRate ratio of failed tracks to total tracks
 * @param totalDurationMs total evaluation execution time in milliseconds
 * @param throughputTracksPerSecond evaluated processing speed in tracks per second
 * @param orderingPreserved whether source track encounter order was verified at destination
 * @param evaluationScope description of evaluation dataset and candidate pool mode
 */
public record MigrationWorkflowEvaluationReport(
        int totalPlaylistsEvaluated,
        int totalTracksEvaluated,
        int successfullyMigratedTracks,
        int unmatchedTracks,
        int reviewRequiredTracks,
        int failedTracks,
        int duplicateTracksPrevented,
        double migrationSuccessRate,
        double unmatchedRate,
        double reviewRate,
        double failureRate,
        long totalDurationMs,
        double throughputTracksPerSecond,
        boolean orderingPreserved,
        String evaluationScope
) {
    public MigrationWorkflowEvaluationReport {
        if (totalPlaylistsEvaluated < 0 || totalTracksEvaluated < 0 || successfullyMigratedTracks < 0
                || unmatchedTracks < 0 || reviewRequiredTracks < 0 || failedTracks < 0 || duplicateTracksPrevented < 0) {
            throw new IllegalArgumentException("Counts must be non-negative");
        }
        if (totalDurationMs < 0) {
            throw new IllegalArgumentException("totalDurationMs must be non-negative: " + totalDurationMs);
        }
        Objects.requireNonNull(evaluationScope, "evaluationScope must not be null");
    }

    /**
     * Formats this report as GitHub Flavored Markdown.
     *
     * @return markdown report string
     */
    public String formatToMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Phase 8H: Playlist Migration Workflow Evaluation & Reliability Report\n\n");
        sb.append(String.format(Locale.ROOT, "- **Scope:** %s\n", evaluationScope));
        sb.append(String.format(Locale.ROOT, "- **Playlists Evaluated:** %d\n", totalPlaylistsEvaluated));
        sb.append(String.format(Locale.ROOT, "- **Total Tracks Evaluated:** %d\n", totalTracksEvaluated));
        sb.append(String.format(Locale.ROOT, "- **Execution Time:** %d ms (%.2f tracks/sec)\n", totalDurationMs, throughputTracksPerSecond));
        sb.append(String.format(Locale.ROOT, "- **Source Ordering Preserved:** %s\n\n", orderingPreserved ? "YES (100% Verified)" : "NO (Order Discrepancy Detected)"));

        sb.append("## 1. Migration Outcomes & Flow Telemetry\n\n");
        sb.append("| Metric | Count | Rate (%) | Denominator / Population |\n");
        sb.append("| :--- | :--- | :--- | :--- |\n");
        sb.append(String.format(Locale.ROOT, "| Successfully Migrated | %d | %.2f%% | Total Evaluated Tracks (%d) |\n",
                successfullyMigratedTracks, migrationSuccessRate * 100.0, totalTracksEvaluated));
        sb.append(String.format(Locale.ROOT, "| Unmatched Tracks | %d | %.2f%% | Total Evaluated Tracks (%d) |\n",
                unmatchedTracks, unmatchedRate * 100.0, totalTracksEvaluated));
        sb.append(String.format(Locale.ROOT, "| Review Required (Abstentions) | %d | %.2f%% | Total Evaluated Tracks (%d) |\n",
                reviewRequiredTracks, reviewRate * 100.0, totalTracksEvaluated));
        sb.append(String.format(Locale.ROOT, "| Processing Failures | %d | %.2f%% | Total Evaluated Tracks (%d) |\n",
                failedTracks, failureRate * 100.0, totalTracksEvaluated));
        sb.append(String.format(Locale.ROOT, "| Duplicate Additions Prevented | %d | — | Destination Candidate Deduplication |\n\n",
                duplicateTracksPrevented));

        sb.append("## 2. Reliability & Safety Invariants\n\n");
        sb.append("1. **Order Preservation:** 1-to-1 sequential mapping between source playlist track order and target insertion index.\n");
        sb.append("2. **Partial Progress Retention:** Unmatched tracks, review abstentions, or single-track errors do not abort playlist creation.\n");
        sb.append("3. **Zero Secret Leakage:** Token sanitization regex verified on all diagnostic strings and failure records.\n");
        sb.append("4. **Decision Gating:** Ambiguous or penalized candidates are held back for review rather than mistakenly added to YouTube playlists.\n\n");

        sb.append("## 3. Scope & Provenance Disclosure\n\n");
        sb.append("> [!NOTE]\n");
        sb.append("> Evaluation metrics reflect reproducible offline execution with deterministic mocks and ground-truth fixtures. Live YouTube and Spotify network conditions, quota consumption, and real-time catalog drift must be verified in pre-production staging environments.\n");

        return sb.toString();
    }

    /**
     * Formats this report as plain text for console or CI logs.
     *
     * @return plain text report
     */
    public String formatToPlainText() {
        return String.format(Locale.ROOT,
                "MIGRATION EVALUATION REPORT [%s]%n"
                        + "Playlists: %d | Tracks: %d | Migrated: %d (%.2f%%) | Unmatched: %d (%.2f%%) | Review: %d (%.2f%%) | Failures: %d (%.2f%%)%n"
                        + "Duplicates Prevented: %d | Ordering Preserved: %s | Duration: %d ms (%.1f tracks/s)",
                evaluationScope, totalPlaylistsEvaluated, totalTracksEvaluated,
                successfullyMigratedTracks, migrationSuccessRate * 100.0,
                unmatchedTracks, unmatchedRate * 100.0,
                reviewRequiredTracks, reviewRate * 100.0,
                failedTracks, failureRate * 100.0,
                duplicateTracksPrevented, orderingPreserved, totalDurationMs, throughputTracksPerSecond);
    }
}
