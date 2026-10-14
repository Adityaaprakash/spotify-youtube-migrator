package com.spotifyyoutube.migrator.matching.benchmark.metric;

import java.util.Optional;

/**
 * Immutable metrics evaluating high-confidence match error rates.
 * <p>
 * Evaluates the empirical safety of automatic acceptance decisions above a defined probability threshold
 * (intended to be 0.90 where empirical probability calibration is active).
 *
 * @param available whether calibrated probabilities were exposed by the engine to compute this metric
 * @param unavailabilityReason diagnostic explanation if probability evaluation is unavailable
 * @param threshold probability threshold applied (e.g. 0.90)
 * @param highConfidenceAcceptedCount number of automated MATCH predictions meeting or exceeding threshold
 * @param highConfidenceIncorrectCount number of incorrect predictions accepted with high confidence
 * @param highConfidenceCorrectCount number of correct predictions accepted with high confidence
 * @param errorRate proportion of high-confidence predictions that were incorrect: incorrect / accepted
 * @param accuracy proportion of high-confidence predictions that were correct: correct / accepted
 */
public record HighConfidenceMetrics(
        boolean available,
        String unavailabilityReason,
        double threshold,
        int highConfidenceAcceptedCount,
        int highConfidenceIncorrectCount,
        int highConfidenceCorrectCount,
        Double errorRate,
        Double accuracy
) {

    public static HighConfidenceMetrics unavailable(String reason, double threshold) {
        return new HighConfidenceMetrics(
                false,
                reason,
                threshold,
                0,
                0,
                0,
                null,
                null
        );
    }

    public Optional<String> getUnavailabilityReason() {
        return Optional.ofNullable(unavailabilityReason);
    }

    public Optional<Double> getErrorRate() {
        return Optional.ofNullable(errorRate);
    }

    public Optional<Double> getAccuracy() {
        return Optional.ofNullable(accuracy);
    }

    public Optional<Double> getErrorRatePercent() {
        return Optional.ofNullable(errorRate).map(v -> Math.round(v * 10000.0) / 100.0);
    }

    public Optional<Double> getAccuracyPercent() {
        return Optional.ofNullable(accuracy).map(v -> Math.round(v * 10000.0) / 100.0);
    }
}
