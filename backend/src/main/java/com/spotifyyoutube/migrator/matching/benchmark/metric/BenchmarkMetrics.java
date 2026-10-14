package com.spotifyyoutube.migrator.matching.benchmark.metric;

import java.util.Objects;

/**
 * Composite container consolidating all evaluation metrics for a benchmark run or slice.
 *
 * @param totalExamples total examples processed
 * @param eligibleExamples eligible examples entering scored metrics
 * @param excludedExamples excluded examples filtered by policy
 * @param ranking candidate ranking metrics (Top-1, Top-3, recall)
 * @param decisionPolicy decision policy metrics (precision, recall, FPR, FNR, review rate)
 * @param highConfidence high-confidence false-match metrics
 * @param calibration probability calibration metrics (Brier score, ECE, buckets)
 * @param errorAnalysis error taxonomy distributions
 */
public record BenchmarkMetrics(
        int totalExamples,
        int eligibleExamples,
        int excludedExamples,
        RankingMetrics ranking,
        DecisionPolicyMetrics decisionPolicy,
        HighConfidenceMetrics highConfidence,
        CalibrationMetrics calibration,
        BenchmarkErrorAnalysis errorAnalysis
) {

    public BenchmarkMetrics {
        Objects.requireNonNull(ranking, "ranking metrics must not be null");
        Objects.requireNonNull(decisionPolicy, "decisionPolicy metrics must not be null");
        Objects.requireNonNull(highConfidence, "highConfidence metrics must not be null");
        Objects.requireNonNull(calibration, "calibration metrics must not be null");
        Objects.requireNonNull(errorAnalysis, "errorAnalysis must not be null");
    }
}
