package com.spotifyyoutube.migrator.matching.benchmark.metric;

import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDatasetSplit;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkExpectedOutcome;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkTrackCategory;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkErrorCategory;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;

import java.util.Map;

/**
 * Immutable aggregated error analysis capturing distribution of error categories
 * overall and sliced by dataset split, track typology, expected outcome, and predicted decision.
 */
public record BenchmarkErrorAnalysis(
        int totalEvaluated,
        Map<BenchmarkErrorCategory, Integer> overallCounts,
        Map<BenchmarkDatasetSplit, Map<BenchmarkErrorCategory, Integer>> countsBySplit,
        Map<BenchmarkTrackCategory, Map<BenchmarkErrorCategory, Integer>> countsByCategory,
        Map<BenchmarkExpectedOutcome, Map<BenchmarkErrorCategory, Integer>> countsByExpectedOutcome,
        Map<MatchDecision, Map<BenchmarkErrorCategory, Integer>> countsByPredictedDecision
) {

    public BenchmarkErrorAnalysis {
        overallCounts = overallCounts != null ? Map.copyOf(overallCounts) : Map.of();
        countsBySplit = countsBySplit != null ? Map.copyOf(countsBySplit) : Map.of();
        countsByCategory = countsByCategory != null ? Map.copyOf(countsByCategory) : Map.of();
        countsByExpectedOutcome = countsByExpectedOutcome != null ? Map.copyOf(countsByExpectedOutcome) : Map.of();
        countsByPredictedDecision = countsByPredictedDecision != null ? Map.copyOf(countsByPredictedDecision) : Map.of();
    }

    public int getOverallCount(BenchmarkErrorCategory category) {
        return overallCounts.getOrDefault(category, 0);
    }

    public int getCountBySplit(BenchmarkDatasetSplit split, BenchmarkErrorCategory category) {
        Map<BenchmarkErrorCategory, Integer> map = countsBySplit.get(split);
        return map != null ? map.getOrDefault(category, 0) : 0;
    }

    public int getCountByCategory(BenchmarkTrackCategory trackCategory, BenchmarkErrorCategory category) {
        Map<BenchmarkErrorCategory, Integer> map = countsByCategory.get(trackCategory);
        return map != null ? map.getOrDefault(category, 0) : 0;
    }

    public boolean hasEvaluableDataForSplit(BenchmarkDatasetSplit split) {
        Map<BenchmarkErrorCategory, Integer> map = countsBySplit.get(split);
        if (map == null) {
            return false;
        }
        return map.values().stream().mapToInt(Integer::intValue).sum() > 0;
    }

    public boolean hasEvaluableDataForCategory(BenchmarkTrackCategory category) {
        Map<BenchmarkErrorCategory, Integer> map = countsByCategory.get(category);
        if (map == null) {
            return false;
        }
        return map.values().stream().mapToInt(Integer::intValue).sum() > 0;
    }
}
