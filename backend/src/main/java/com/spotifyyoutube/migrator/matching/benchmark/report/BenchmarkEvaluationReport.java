package com.spotifyyoutube.migrator.matching.benchmark.report;

import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDatasetSplit;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkTrackCategory;
import com.spotifyyoutube.migrator.matching.benchmark.metric.BenchmarkMetrics;
import com.spotifyyoutube.migrator.matching.benchmark.runner.EvaluationExclusionReason;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Structured report model capturing comprehensive benchmark evaluation outcomes.
 */
public record BenchmarkEvaluationReport(
        String datasetVersion,
        String datasetDescription,
        String datasetCreatedAt,
        int totalExamples,
        int eligibleExamples,
        int excludedExamples,
        int failedExamples,
        Map<EvaluationExclusionReason, Integer> exclusionBreakdown,
        String candidatePoolStrategy,
        Double candidatePoolCoveragePercent,
        BenchmarkMetrics overallMetrics,
        Map<BenchmarkDatasetSplit, BenchmarkMetrics> metricsBySplit,
        Map<BenchmarkTrackCategory, BenchmarkMetrics> metricsByCategory,
        List<String> limitations,
        long executionDurationMs
) {

    public BenchmarkEvaluationReport {
        Objects.requireNonNull(datasetVersion, "datasetVersion must not be null");
        Objects.requireNonNull(candidatePoolStrategy, "candidatePoolStrategy must not be null");
        Objects.requireNonNull(overallMetrics, "overallMetrics must not be null");
        exclusionBreakdown = exclusionBreakdown != null ? Map.copyOf(exclusionBreakdown) : Map.of();
        metricsBySplit = metricsBySplit != null ? Map.copyOf(metricsBySplit) : Map.of();
        metricsByCategory = metricsByCategory != null ? Map.copyOf(metricsByCategory) : Map.of();
        limitations = limitations != null ? List.copyOf(limitations) : List.of();
    }
}
