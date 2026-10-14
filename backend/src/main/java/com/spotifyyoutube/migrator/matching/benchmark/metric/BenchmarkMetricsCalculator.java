package com.spotifyyoutube.migrator.matching.benchmark.metric;

import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkExampleResult;

import java.util.List;
import java.util.Objects;

/**
 * Top-level orchestrator computing the full suite of evaluation metrics from benchmark results.
 */
public class BenchmarkMetricsCalculator {

    private final RankingMetricsCalculator rankingCalculator;
    private final DecisionPolicyMetricsCalculator decisionCalculator;
    private final HighConfidenceMetricsCalculator highConfidenceCalculator;
    private final CalibrationMetricsCalculator calibrationCalculator;
    private final ErrorAnalysisCalculator errorAnalysisCalculator;

    public BenchmarkMetricsCalculator() {
        this(
                new RankingMetricsCalculator(),
                new DecisionPolicyMetricsCalculator(),
                new HighConfidenceMetricsCalculator(),
                new CalibrationMetricsCalculator(),
                new ErrorAnalysisCalculator()
        );
    }

    public BenchmarkMetricsCalculator(
            RankingMetricsCalculator rankingCalculator,
            DecisionPolicyMetricsCalculator decisionCalculator,
            HighConfidenceMetricsCalculator highConfidenceCalculator,
            CalibrationMetricsCalculator calibrationCalculator,
            ErrorAnalysisCalculator errorAnalysisCalculator
    ) {
        this.rankingCalculator = Objects.requireNonNull(rankingCalculator, "rankingCalculator must not be null");
        this.decisionCalculator = Objects.requireNonNull(decisionCalculator, "decisionCalculator must not be null");
        this.highConfidenceCalculator = Objects.requireNonNull(
                highConfidenceCalculator, "highConfidenceCalculator must not be null");
        this.calibrationCalculator = Objects.requireNonNull(
                calibrationCalculator, "calibrationCalculator must not be null");
        this.errorAnalysisCalculator = Objects.requireNonNull(
                errorAnalysisCalculator, "errorAnalysisCalculator must not be null");
    }

    /**
     * Calculates all metrics for a list of benchmark example results.
     *
     * @param results list of per-example benchmark results
     * @return consolidated BenchmarkMetrics
     */
    public BenchmarkMetrics calculateAll(List<BenchmarkExampleResult> results) {
        List<BenchmarkExampleResult> safeResults = results != null ? results : List.of();

        int total = safeResults.size();
        int eligible = (int) safeResults.stream().filter(BenchmarkExampleResult::eligible).count();
        int excluded = total - eligible;

        RankingMetrics ranking = rankingCalculator.calculate(safeResults);
        DecisionPolicyMetrics decision = decisionCalculator.calculate(safeResults);
        HighConfidenceMetrics highConfidence = highConfidenceCalculator.calculate(safeResults);
        CalibrationMetrics calibration = calibrationCalculator.calculate(safeResults);
        BenchmarkErrorAnalysis errorAnalysis = errorAnalysisCalculator.calculate(safeResults);

        return new BenchmarkMetrics(
                total,
                eligible,
                excluded,
                ranking,
                decision,
                highConfidence,
                calibration,
                errorAnalysis
        );
    }
}
