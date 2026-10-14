package com.spotifyyoutube.migrator.matching.benchmark.report;

import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDatasetSplit;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkTrackCategory;
import com.spotifyyoutube.migrator.matching.benchmark.metric.BenchmarkMetrics;
import com.spotifyyoutube.migrator.matching.benchmark.metric.CalibrationBucket;
import com.spotifyyoutube.migrator.matching.benchmark.metric.DecisionPolicyMetrics;
import com.spotifyyoutube.migrator.matching.benchmark.metric.RankingMetrics;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkErrorCategory;
import com.spotifyyoutube.migrator.matching.benchmark.runner.EvaluationExclusionReason;

import java.util.Locale;
import java.util.Map;

/**
 * Formats a {@link BenchmarkEvaluationReport} into human-readable Markdown and plain text.
 */
public class BenchmarkReportFormatter {

    /**
     * Formats the report as a GitHub Flavored Markdown document.
     */
    public String formatToMarkdown(BenchmarkEvaluationReport report) {
        StringBuilder sb = new StringBuilder();

        sb.append("# Music Matching Benchmark Evaluation Report\n\n");
        sb.append(String.format(Locale.ROOT, "- **Dataset Version:** `%s` (%s)\n",
                report.datasetVersion(), report.datasetCreatedAt()));
        sb.append(String.format(Locale.ROOT, "- **Dataset Description:** %s\n", report.datasetDescription()));
        sb.append(String.format(Locale.ROOT, "- **Candidate Pool Strategy:** `%s`\n", report.candidatePoolStrategy()));
        sb.append(String.format(Locale.ROOT, "- **Total Execution Duration:** %d ms\n\n", report.executionDurationMs()));

        // 1. Eligibility & Coverage
        sb.append("## 1. Dataset Scope & Eligibility\n\n");
        sb.append("| Metric | Count | Percentage |\n");
        sb.append("| :--- | :--- | :--- |\n");
        sb.append(String.format(Locale.ROOT, "| Total Examples | %d | 100.0%% |\n", report.totalExamples()));
        double eligPct = report.totalExamples() > 0 ? (report.eligibleExamples() * 100.0 / report.totalExamples()) : 0.0;
        sb.append(String.format(Locale.ROOT, "| Eligible for Scoring | %d | %.1f%% |\n", report.eligibleExamples(), eligPct));
        double exclPct = report.totalExamples() > 0 ? (report.excludedExamples() * 100.0 / report.totalExamples()) : 0.0;
        sb.append(String.format(Locale.ROOT, "| Excluded Records | %d | %.1f%% |\n", report.excludedExamples(), exclPct));
        sb.append(String.format(Locale.ROOT, "| Execution Failures | %d | %.1f%% |\n\n", report.failedExamples(),
                report.totalExamples() > 0 ? (report.failedExamples() * 100.0 / report.totalExamples()) : 0.0));

        if (report.excludedExamples() > 0) {
            sb.append("### Exclusion Breakdown\n\n");
            for (Map.Entry<EvaluationExclusionReason, Integer> entry : report.exclusionBreakdown().entrySet()) {
                if (entry.getValue() > 0 && entry.getKey() != EvaluationExclusionReason.NONE) {
                    sb.append(String.format(Locale.ROOT, "- **`%s`:** %d example(s)\n",
                            entry.getKey().name(), entry.getValue()));
                }
            }
            sb.append("\n");
        }

        // 2. Candidate Ranking Metrics
        RankingMetrics ranking = report.overallMetrics().ranking();
        sb.append("## 2. Candidate Ranking Performance\n\n");
        if (ranking.hasEvaluableData()) {
            sb.append("| Metric | Evaluated | Present Subset | Percentage |\n");
            sb.append("| :--- | :--- | :--- | :--- |\n");
            sb.append(String.format(Locale.ROOT, "| Target Recall in Pool | %d / %d | — | %s%% |\n",
                    ranking.targetPresentCount(), ranking.eligiblePositiveMatches(),
                    ranking.getCandidateRecallRatePercent().map(Object::toString).orElse("N/A")));
            sb.append(String.format(Locale.ROOT, "| Top-1 Accuracy (All) | %d / %d | — | %s%% |\n",
                    ranking.top1MatchCount(), ranking.eligiblePositiveMatches(),
                    ranking.getTop1AccuracyAllPercent().map(Object::toString).orElse("N/A")));
            sb.append(String.format(Locale.ROOT, "| Top-1 Accuracy (Present) | — | %d / %d | %s%% |\n",
                    ranking.top1MatchCount(), ranking.targetPresentCount(),
                    ranking.getTop1AccuracyPresentPercent().map(Object::toString).orElse("N/A")));
            sb.append(String.format(Locale.ROOT, "| Top-3 Accuracy (All) | %d / %d | — | %s%% |\n",
                    ranking.top3MatchCount(), ranking.eligiblePositiveMatches(),
                    ranking.getTop3AccuracyAllPercent().map(Object::toString).orElse("N/A")));
            sb.append(String.format(Locale.ROOT, "| Top-3 Accuracy (Present) | — | %d / %d | %s%% |\n\n",
                    ranking.top3MatchCount(), ranking.targetPresentCount(),
                    ranking.getTop3AccuracyPresentPercent().map(Object::toString).orElse("N/A")));
        } else {
            sb.append("*No eligible positive match examples evaluated.*\n\n");
        }

        // 3. Decision Policy & Confusion Matrix
        DecisionPolicyMetrics decision = report.overallMetrics().decisionPolicy();
        sb.append("## 3. Decision Policy Performance\n\n");
        if (decision.hasEvaluableData()) {
            sb.append("### Decision Confusion Matrix\n\n");
            sb.append("| Ground Truth \\ Prediction | MATCH (Auto-Accept) | REVIEW_REQUIRED | NO_MATCH (Reject) |\n");
            sb.append("| :--- | :--- | :--- | :--- |\n");
            sb.append(String.format(Locale.ROOT, "| **MATCH (%d)** | %d (Correct) + %d (Wrong) | %d | %d (Incorrect Reject) |\n",
                    decision.totalExpectedMatch(), decision.correctAutoAccept(), decision.wrongCandidateAccepted(),
                    decision.reviewRequiredPositive(), decision.incorrectRejection()));
            sb.append(String.format(Locale.ROOT, "| **NO_MATCH (%d)** | %d (False Accept) | %d | %d (Correct Reject) |\n\n",
                    decision.totalExpectedNoMatch(), decision.falseAcceptanceOfNoMatch(),
                    decision.reviewRequiredNoMatch(), decision.correctNoMatch()));

            sb.append("### Classification & Coverage Metrics\n\n");
            sb.append("| Metric | Formula | Value |\n");
            sb.append("| :--- | :--- | :--- |\n");
            sb.append(String.format(Locale.ROOT, "| Precision (Auto-Accept) | %d / %d | %s%% |\n",
                    decision.correctAutoAccept(), decision.totalAutoAccepted(),
                    decision.getPrecisionPercent().map(Object::toString).orElse("N/A")));
            sb.append(String.format(Locale.ROOT, "| Recall (Automated Matches) | %d / %d | %s%% |\n",
                    decision.correctAutoAccept(), decision.totalExpectedMatch(),
                    decision.getRecallPercent().map(Object::toString).orElse("N/A")));
            sb.append(String.format(Locale.ROOT, "| False Positive Rate | %d / %d | %s%% |\n",
                    decision.falseAcceptanceOfNoMatch(), decision.totalExpectedNoMatch(),
                    decision.getFalsePositiveRatePercent().map(Object::toString).orElse("N/A")));
            sb.append(String.format(Locale.ROOT, "| NO_MATCH Precision | %d / %d | %s |\n",
                    decision.correctNoMatch(), decision.totalNoMatchPredicted(),
                    decision.getNoMatchPrecision().map(v -> String.format(Locale.ROOT, "%.4f", v)).orElse("N/A")));
            sb.append(String.format(Locale.ROOT, "| NO_MATCH Recall | %d / %d | %s |\n",
                    decision.correctNoMatch(), decision.totalExpectedNoMatch(),
                    decision.getNoMatchRecall().map(v -> String.format(Locale.ROOT, "%.4f", v)).orElse("N/A")));
            sb.append(String.format(Locale.ROOT, "| Automatic Acceptance Rate | %d / %d | %s%% |\n",
                    decision.totalAutoAccepted(), decision.totalEligible(),
                    decision.getAutomaticAcceptanceRatePercent().map(Object::toString).orElse("N/A")));
            sb.append(String.format(Locale.ROOT, "| Review Required Rate | %d / %d | %s%% |\n",
                    decision.totalReviewRequired(), decision.totalEligible(),
                    decision.getReviewRequiredRatePercent().map(Object::toString).orElse("N/A")));
            sb.append(String.format(Locale.ROOT, "| Overall Decision Accuracy | (%d + %d) / %d | %.2f%% |\n\n",
                    decision.correctAutoAccept(), decision.correctNoMatch(), decision.totalEligible(),
                    decision.getOverallAccuracy().map(v -> v * 100.0).orElse(0.0)));
        } else {
            sb.append("*No evaluable decision data available.*\n\n");
        }

        // 4. High Confidence & Calibration
        sb.append("## 4. Probability Calibration & High-Confidence Reliability\n\n");
        if (report.overallMetrics().highConfidence().available()) {
            sb.append(String.format(Locale.ROOT, "- **High-Confidence Threshold:** `%.2f`\n",
                    report.overallMetrics().highConfidence().threshold()));
            sb.append(String.format(Locale.ROOT, "- **High-Confidence Accepted Count:** %d\n",
                    report.overallMetrics().highConfidence().highConfidenceAcceptedCount()));
            sb.append(String.format(Locale.ROOT, "- **High-Confidence Incorrect Matches:** %d\n",
                    report.overallMetrics().highConfidence().highConfidenceIncorrectCount()));
            sb.append(String.format(Locale.ROOT, "- **High-Confidence Error Rate:** %s%%\n\n",
                    report.overallMetrics().highConfidence().getErrorRatePercent().map(Object::toString).orElse("N/A")));
        } else {
            sb.append("> [!NOTE]\n");
            sb.append("> **High-Confidence Probability Evaluation:** ")
                    .append(report.overallMetrics().highConfidence().unavailabilityReason())
                    .append("\n\n");
        }

        if (report.overallMetrics().calibration().available()) {
            sb.append(String.format(Locale.ROOT, "- **Brier Score:** `%.4f`\n",
                    report.overallMetrics().calibration().brierScore()));
            sb.append(String.format(Locale.ROOT, "- **Baseline Brier Score:** `%.4f`\n",
                    report.overallMetrics().calibration().baselineBrierScore()));
            sb.append(String.format(Locale.ROOT, "- **Expected Calibration Error (ECE):** `%.4f`\n\n",
                    report.overallMetrics().calibration().expectedCalibrationError()));

            sb.append("### Reliability Diagram Bins\n\n");
            sb.append("| Bin Range | Count | Mean Confidence | Observed Accuracy |\n");
            sb.append("| :--- | :--- | :--- | :--- |\n");
            for (CalibrationBucket bucket : report.overallMetrics().calibration().buckets()) {
                if (bucket.isEmpty()) {
                    sb.append(String.format(Locale.ROOT, "| [%.2f, %.2f) | 0 | — | — |\n",
                            bucket.lowerBound(), bucket.upperBound()));
                } else {
                    sb.append(String.format(Locale.ROOT, "| [%.2f, %.2f) | %d | %.4f | %.4f |\n",
                            bucket.lowerBound(), bucket.upperBound(), bucket.count(),
                            bucket.meanConfidence(), bucket.accuracy()));
                }
            }
            sb.append("\n");
        }

        // 5. Performance by Split
        sb.append("## 5. Performance by Dataset Split\n\n");
        sb.append("| Split | Total | Eligible | Top-1 (Present) | Precision | Recall | Review Rate |\n");
        sb.append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- |\n");
        for (BenchmarkDatasetSplit split : BenchmarkDatasetSplit.values()) {
            BenchmarkMetrics m = report.metricsBySplit().get(split);
            if (m != null && m.eligibleExamples() > 0) {
                sb.append(String.format(Locale.ROOT, "| **%s** | %d | %d | %s | %s | %s | %s |\n",
                        split.name(),
                        m.totalExamples(),
                        m.eligibleExamples(),
                        m.ranking().getTop1AccuracyPresentPercent().map(v -> v + "%").orElse("N/A"),
                        m.decisionPolicy().getPrecisionPercent().map(v -> v + "%").orElse("N/A"),
                        m.decisionPolicy().getRecallPercent().map(v -> v + "%").orElse("N/A"),
                        m.decisionPolicy().getReviewRequiredRatePercent().map(v -> v + "%").orElse("N/A")));
            } else {
                sb.append(String.format(Locale.ROOT, "| **%s** | %d | 0 | *No evaluable data* | — | — | — |\n",
                        split.name(), m != null ? m.totalExamples() : 0));
            }
        }
        sb.append("\n");

        // 6. Limitations & Notes
        sb.append("## 6. Evaluation Discipline & Limitations\n\n");
        for (String lim : report.limitations()) {
            sb.append("- ").append(lim).append("\n");
        }
        sb.append("\n");

        return sb.toString();
    }

    /**
     * Formats the report as concise plain text.
     */
    public String formatToPlainText(BenchmarkEvaluationReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("================================================================================\n");
        sb.append("                   BENCHMARK EVALUATION REPORT\n");
        sb.append("================================================================================\n");
        sb.append(String.format(Locale.ROOT, "Dataset Version: %s (%s)\n", report.datasetVersion(), report.datasetCreatedAt()));
        sb.append(String.format(Locale.ROOT, "Candidate Pool Strategy: %s\n", report.candidatePoolStrategy()));
        sb.append(String.format(Locale.ROOT, "Total Examples: %d | Eligible: %d | Excluded: %d | Failed: %d\n",
                report.totalExamples(), report.eligibleExamples(), report.excludedExamples(), report.failedExamples()));
        sb.append("--------------------------------------------------------------------------------\n");

        RankingMetrics r = report.overallMetrics().ranking();
        if (r.hasEvaluableData()) {
            sb.append(String.format(Locale.ROOT, "Ranking: Top-1 (Present)=%s | Top-3 (Present)=%s | Pool Recall=%s\n",
                    r.getTop1AccuracyPresentPercent().map(v -> v + "%").orElse("N/A"),
                    r.getTop3AccuracyPresentPercent().map(v -> v + "%").orElse("N/A"),
                    r.getCandidateRecallRatePercent().map(v -> v + "%").orElse("N/A")));
        }

        DecisionPolicyMetrics d = report.overallMetrics().decisionPolicy();
        if (d.hasEvaluableData()) {
            sb.append(String.format(Locale.ROOT, "Decision: Precision=%s | Recall=%s | FPR=%s | ReviewRate=%s | AutoAccept=%s\n",
                    d.getPrecisionPercent().map(v -> v + "%").orElse("N/A"),
                    d.getRecallPercent().map(v -> v + "%").orElse("N/A"),
                    d.getFalsePositiveRatePercent().map(v -> v + "%").orElse("N/A"),
                    d.getReviewRequiredRatePercent().map(v -> v + "%").orElse("N/A"),
                    d.getAutomaticAcceptanceRatePercent().map(v -> v + "%").orElse("N/A")));
        }
        sb.append("================================================================================\n");
        return sb.toString();
    }
}
