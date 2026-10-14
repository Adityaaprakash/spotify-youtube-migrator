package com.spotifyyoutube.migrator.matching.benchmark.report;

import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDatasetSplit;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkTrackCategory;
import com.spotifyyoutube.migrator.matching.benchmark.metric.BenchmarkMetrics;
import com.spotifyyoutube.migrator.matching.benchmark.metric.BenchmarkMetricsCalculator;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkErrorCategory;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkExampleResult;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkRunResult;
import com.spotifyyoutube.migrator.matching.benchmark.runner.EvaluationExclusionReason;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Generates structured {@link BenchmarkEvaluationReport}s from benchmark run results.
 */
public class BenchmarkReportGenerator {

    private final BenchmarkMetricsCalculator metricsCalculator;

    public BenchmarkReportGenerator() {
        this(new BenchmarkMetricsCalculator());
    }

    public BenchmarkReportGenerator(BenchmarkMetricsCalculator metricsCalculator) {
        this.metricsCalculator = Objects.requireNonNull(metricsCalculator, "metricsCalculator must not be null");
    }

    /**
     * Generates a complete evaluation report from a benchmark run result.
     *
     * @param runResult executed benchmark run result
     * @return structured evaluation report
     */
    public BenchmarkEvaluationReport generateReport(BenchmarkRunResult runResult) {
        Objects.requireNonNull(runResult, "runResult must not be null");
        List<BenchmarkExampleResult> results = runResult.exampleResults();

        // 1. Overall Metrics
        BenchmarkMetrics overallMetrics = metricsCalculator.calculateAll(results);

        // 2. Metrics Sliced by Split
        Map<BenchmarkDatasetSplit, BenchmarkMetrics> metricsBySplit = new EnumMap<>(BenchmarkDatasetSplit.class);
        for (BenchmarkDatasetSplit split : BenchmarkDatasetSplit.values()) {
            List<BenchmarkExampleResult> splitResults = runResult.resultsBySplit(split);
            metricsBySplit.put(split, metricsCalculator.calculateAll(splitResults));
        }

        // 3. Metrics Sliced by Category
        Map<BenchmarkTrackCategory, BenchmarkMetrics> metricsByCategory = new EnumMap<>(BenchmarkTrackCategory.class);
        for (BenchmarkTrackCategory category : BenchmarkTrackCategory.values()) {
            List<BenchmarkExampleResult> catResults = runResult.resultsByCategory(category);
            metricsByCategory.put(category, metricsCalculator.calculateAll(catResults));
        }

        // 4. Exclusion Breakdown
        Map<EvaluationExclusionReason, Integer> exclusionBreakdown = new EnumMap<>(EvaluationExclusionReason.class);
        for (EvaluationExclusionReason reason : EvaluationExclusionReason.values()) {
            exclusionBreakdown.put(reason, 0);
        }
        int failedCount = 0;
        for (BenchmarkExampleResult r : results) {
            if (!r.eligible()) {
                exclusionBreakdown.merge(r.exclusionReason(), 1, Integer::sum);
            }
            if (r.errorClassification() == BenchmarkErrorCategory.EXECUTION_FAILURE) {
                failedCount++;
            }
        }

        // 5. Candidate Pool Coverage
        Double coveragePercent = null;
        if (overallMetrics.ranking().hasEvaluableData()) {
            coveragePercent = overallMetrics.ranking().getCandidateRecallRatePercent().orElse(null);
        }

        // 6. Assembled Limitations Notes
        List<String> limitations = new ArrayList<>();
        if ("CONTROLLED_SYNTHETIC_DECOYS".equals(runResult.candidatePoolStrategy())) {
            limitations.add("Candidate pools use controlled synthetic decoys (covers, live variants, remixes) "
                    + "for deterministic component evaluation. Realistic end-to-end YouTube discovery ranking evaluation "
                    + "is limited until pre-recorded multi-candidate discovery fixtures are integrated.");
        } else if ("TARGET_ONLY_NON_RANKING".equals(runResult.candidatePoolStrategy())) {
            limitations.add("Candidate pools contain only the single ground-truth target candidate. "
                    + "Top-1 and Top-3 accuracy metrics are trivial and do not measure multi-candidate ranking discrimination.");
        }

        if (!overallMetrics.highConfidence().available()) {
            limitations.add("Statistical probability calibration metrics are unavailable because the Phase 7 matching engine "
                    + "operates under DefaultMatchProbabilityCalibrator in baseline uncalibrated mode (calibratedProbability is null). "
                    + "Empirical probability evaluation requires an active, validated Platt-scaling calibration model.");
        }

        limitations.add("The TEST split (held-out partition) must remain strictly un-tuned in accordance with evaluation discipline.");

        return new BenchmarkEvaluationReport(
                runResult.datasetVersion(),
                runResult.datasetDescription(),
                runResult.datasetCreatedAt(),
                runResult.totalDatasetExamples(),
                runResult.eligibleCount(),
                runResult.excludedCount(),
                failedCount,
                exclusionBreakdown,
                runResult.candidatePoolStrategy(),
                coveragePercent,
                overallMetrics,
                metricsBySplit,
                metricsByCategory,
                limitations,
                runResult.totalDurationMs()
        );
    }
}
