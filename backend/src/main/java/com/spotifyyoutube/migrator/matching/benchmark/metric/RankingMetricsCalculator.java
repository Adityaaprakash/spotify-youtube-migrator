package com.spotifyyoutube.migrator.matching.benchmark.metric;

import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkExampleResult;

import java.util.List;
import java.util.Objects;

/**
 * Calculator for candidate ranking metrics (Top-1 and Top-3 accuracy).
 */
public class RankingMetricsCalculator {

    /**
     * Calculates candidate ranking metrics across a collection of benchmark results.
     * <p>
     * Evaluates only eligible positive {@code MATCH} examples. Excluded examples and
     * legitimate {@code NO_MATCH} examples do not enter ranking calculations.
     *
     * @param results list of per-example benchmark results
     * @return computed ranking metrics
     */
    public RankingMetrics calculate(List<BenchmarkExampleResult> results) {
        if (results == null || results.isEmpty()) {
            return empty();
        }

        List<BenchmarkExampleResult> eligibleMatches = results.stream()
                .filter(BenchmarkExampleResult::eligible)
                .filter(r -> r.expectedOutcome().isMatch())
                .toList();

        int eligibleTotal = eligibleMatches.size();
        if (eligibleTotal == 0) {
            return empty();
        }

        int targetPresentCount = 0;
        int targetAbsentCount = 0;
        int top1Count = 0;
        int top3Count = 0;

        for (BenchmarkExampleResult result : eligibleMatches) {
            if (result.targetPresentInCandidatePool()) {
                targetPresentCount++;
            } else {
                targetAbsentCount++;
            }

            if (result.isTop1Match()) {
                top1Count++;
            }
            if (result.isTop3Match()) {
                top3Count++;
            }
        }

        Double top1All = (double) top1Count / eligibleTotal;
        Double top3All = (double) top3Count / eligibleTotal;
        Double candidateRecall = (double) targetPresentCount / eligibleTotal;

        Double top1Present = targetPresentCount > 0
                ? (double) top1Count / targetPresentCount
                : null;
        Double top3Present = targetPresentCount > 0
                ? (double) top3Count / targetPresentCount
                : null;

        return new RankingMetrics(
                eligibleTotal,
                targetPresentCount,
                targetAbsentCount,
                top1Count,
                top3Count,
                round4(top1All),
                round4(top1Present),
                round4(top3All),
                round4(top3Present),
                round4(candidateRecall)
        );
    }

    private RankingMetrics empty() {
        return new RankingMetrics(0, 0, 0, 0, 0, null, null, null, null, null);
    }

    private Double round4(Double value) {
        if (value == null) {
            return null;
        }
        return Math.round(value * 10000.0) / 10000.0;
    }
}
