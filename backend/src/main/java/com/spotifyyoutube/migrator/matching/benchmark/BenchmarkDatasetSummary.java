package com.spotifyyoutube.migrator.matching.benchmark;

import java.util.Map;

/**
 * Summary telemetry and counts for a benchmark dataset.
 *
 * @param totalExamples total number of examples in the dataset
 * @param verifiedExamples number of human-verified examples
 * @param unverifiedExamples number of unverified examples
 * @param disputedExamples number of disputed examples
 * @param positiveMatches number of positive match examples
 * @param noMatches number of legitimate no-match examples
 * @param countBySplit mapping of dataset split to example count
 * @param countByCategory mapping of track category to example count
 */
public record BenchmarkDatasetSummary(
        int totalExamples,
        int verifiedExamples,
        int unverifiedExamples,
        int disputedExamples,
        int positiveMatches,
        int noMatches,
        Map<BenchmarkDatasetSplit, Integer> countBySplit,
        Map<BenchmarkTrackCategory, Integer> countByCategory
) {

    public BenchmarkDatasetSummary {
        countBySplit = countBySplit != null ? Map.copyOf(countBySplit) : Map.of();
        countByCategory = countByCategory != null ? Map.copyOf(countByCategory) : Map.of();
    }

    public int verifiedCount() {
        return verifiedExamples;
    }

    public int unverifiedCount() {
        return unverifiedExamples;
    }

    public int disputedCount() {
        return disputedExamples;
    }

    public int matchCount() {
        return positiveMatches;
    }

    public int noMatchCount() {
        return noMatches;
    }

    public Map<BenchmarkDatasetSplit, Integer> splitCounts() {
        return countBySplit;
    }

    public Map<BenchmarkTrackCategory, Integer> categoryCounts() {
        return countByCategory;
    }
}
