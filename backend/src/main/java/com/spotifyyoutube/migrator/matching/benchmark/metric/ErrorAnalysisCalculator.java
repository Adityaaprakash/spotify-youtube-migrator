package com.spotifyyoutube.migrator.matching.benchmark.metric;

import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDatasetSplit;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkExpectedOutcome;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkTrackCategory;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkErrorCategory;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkExampleResult;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Calculator for structured error analysis breakdowns.
 */
public class ErrorAnalysisCalculator {

    /**
     * Aggregates error categories across all benchmark results, sliced across splits, categories, and decisions.
     *
     * @param results list of per-example benchmark results
     * @return structured error analysis aggregation
     */
    public BenchmarkErrorAnalysis calculate(List<BenchmarkExampleResult> results) {
        if (results == null || results.isEmpty()) {
            return empty();
        }

        Map<BenchmarkErrorCategory, Integer> overallCounts = new EnumMap<>(BenchmarkErrorCategory.class);
        for (BenchmarkErrorCategory cat : BenchmarkErrorCategory.values()) {
            overallCounts.put(cat, 0);
        }

        Map<BenchmarkDatasetSplit, Map<BenchmarkErrorCategory, Integer>> countsBySplit =
                new EnumMap<>(BenchmarkDatasetSplit.class);
        for (BenchmarkDatasetSplit split : BenchmarkDatasetSplit.values()) {
            Map<BenchmarkErrorCategory, Integer> map = new EnumMap<>(BenchmarkErrorCategory.class);
            for (BenchmarkErrorCategory cat : BenchmarkErrorCategory.values()) {
                map.put(cat, 0);
            }
            countsBySplit.put(split, map);
        }

        Map<BenchmarkTrackCategory, Map<BenchmarkErrorCategory, Integer>> countsByCategory =
                new EnumMap<>(BenchmarkTrackCategory.class);
        for (BenchmarkTrackCategory cat : BenchmarkTrackCategory.values()) {
            Map<BenchmarkErrorCategory, Integer> map = new EnumMap<>(BenchmarkErrorCategory.class);
            for (BenchmarkErrorCategory err : BenchmarkErrorCategory.values()) {
                map.put(err, 0);
            }
            countsByCategory.put(cat, map);
        }

        Map<BenchmarkExpectedOutcome, Map<BenchmarkErrorCategory, Integer>> countsByOutcome =
                new EnumMap<>(BenchmarkExpectedOutcome.class);
        for (BenchmarkExpectedOutcome outcome : BenchmarkExpectedOutcome.values()) {
            Map<BenchmarkErrorCategory, Integer> map = new EnumMap<>(BenchmarkErrorCategory.class);
            for (BenchmarkErrorCategory err : BenchmarkErrorCategory.values()) {
                map.put(err, 0);
            }
            countsByOutcome.put(outcome, map);
        }

        Map<MatchDecision, Map<BenchmarkErrorCategory, Integer>> countsByDecision =
                new EnumMap<>(MatchDecision.class);
        for (MatchDecision dec : MatchDecision.values()) {
            Map<BenchmarkErrorCategory, Integer> map = new EnumMap<>(BenchmarkErrorCategory.class);
            for (BenchmarkErrorCategory err : BenchmarkErrorCategory.values()) {
                map.put(err, 0);
            }
            countsByDecision.put(dec, map);
        }

        int totalEvaluated = 0;
        for (BenchmarkExampleResult r : results) {
            BenchmarkErrorCategory errorCat = r.errorClassification();
            overallCounts.merge(errorCat, 1, Integer::sum);
            countsBySplit.get(r.split()).merge(errorCat, 1, Integer::sum);
            countsByCategory.get(r.category()).merge(errorCat, 1, Integer::sum);
            countsByOutcome.get(r.expectedOutcome()).merge(errorCat, 1, Integer::sum);
            if (r.predictedDecision() != null) {
                countsByDecision.get(r.predictedDecision()).merge(errorCat, 1, Integer::sum);
            }
            totalEvaluated++;
        }

        return new BenchmarkErrorAnalysis(
                totalEvaluated,
                overallCounts,
                countsBySplit,
                countsByCategory,
                countsByOutcome,
                countsByDecision
        );
    }

    private BenchmarkErrorAnalysis empty() {
        return new BenchmarkErrorAnalysis(0, Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }
}
