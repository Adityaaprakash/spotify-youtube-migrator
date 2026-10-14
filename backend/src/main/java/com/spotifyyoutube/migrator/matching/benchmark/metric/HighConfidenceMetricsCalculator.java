package com.spotifyyoutube.migrator.matching.benchmark.metric;

import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkExampleResult;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;

import java.util.List;

/**
 * Calculator for high-confidence false-match rates.
 */
public class HighConfidenceMetricsCalculator {

    public static final double DEFAULT_THRESHOLD = 0.90;

    /**
     * Calculates high-confidence error metrics with default threshold of 0.90.
     *
     * @param results per-example benchmark results
     * @return computed high-confidence metrics
     */
    public HighConfidenceMetrics calculate(List<BenchmarkExampleResult> results) {
        return calculate(results, DEFAULT_THRESHOLD);
    }

    /**
     * Calculates high-confidence error metrics with a specified probability threshold.
     *
     * @param results per-example benchmark results
     * @param threshold probability threshold (0.0 to 1.0)
     * @return computed high-confidence metrics
     */
    public HighConfidenceMetrics calculate(List<BenchmarkExampleResult> results, double threshold) {
        if (results == null || results.isEmpty()) {
            return HighConfidenceMetrics.unavailable("No benchmark results provided", threshold);
        }

        List<BenchmarkExampleResult> eligible = results.stream()
                .filter(BenchmarkExampleResult::eligible)
                .toList();

        if (eligible.isEmpty()) {
            return HighConfidenceMetrics.unavailable("No eligible benchmark results available", threshold);
        }

        // Check if any results expose calibrated probabilities
        boolean hasProbabilities = eligible.stream()
                .anyMatch(r -> r.predictedProbability() != null);

        if (!hasProbabilities) {
            return HighConfidenceMetrics.unavailable(
                    "Matching engine did not expose calibrated probabilities (calibratedProbability is null; "
                            + "DefaultMatchProbabilityCalibrator operates in uncalibrated baseline mode). "
                            + "Heuristic composite similarity scores are honestly preserved and not misrepresented as probabilities.",
                    threshold
            );
        }

        int acceptedCount = 0;
        int incorrectCount = 0;
        int correctCount = 0;

        for (BenchmarkExampleResult r : eligible) {
            if (r.predictedDecision() == MatchDecision.MATCH && r.predictedProbability() != null) {
                if (r.predictedProbability() >= threshold) {
                    acceptedCount++;
                    if (r.isCorrectAutoAccept()) {
                        correctCount++;
                    } else {
                        incorrectCount++;
                    }
                }
            }
        }

        Double errorRate = acceptedCount > 0
                ? (double) incorrectCount / acceptedCount
                : null;
        Double accuracy = acceptedCount > 0
                ? (double) correctCount / acceptedCount
                : null;

        return new HighConfidenceMetrics(
                true,
                null,
                threshold,
                acceptedCount,
                incorrectCount,
                correctCount,
                round4(errorRate),
                round4(accuracy)
        );
    }

    private Double round4(Double value) {
        if (value == null) {
            return null;
        }
        return Math.round(value * 10000.0) / 10000.0;
    }
}
