package com.spotifyyoutube.migrator.matching.benchmark.metric;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Immutable evaluation metrics capturing statistical probability calibration quality.
 * <p>
 * Includes Brier score, baseline comparative Brier score, Expected Calibration Error (ECE),
 * reliability diagram bins, and mean confidence for correct vs. incorrect predictions.
 *
 * @param available whether empirical probabilities were present to evaluate calibration
 * @param unavailabilityReason diagnostic explanation if calibration evaluation is unavailable
 * @param evaluableCount number of eligible examples possessing non-null calibrated probabilities
 * @param brierScore empirical Brier score: mean squared error between probability and true outcome
 * @param baselineBrierScore comparative baseline Brier score: variance of empirical positive base rate
 * @param expectedCalibrationError Expected Calibration Error (ECE) across reliability bins
 * @param buckets list of reliability histogram buckets
 * @param meanConfidenceCorrect mean predicted probability for correct matches
 * @param meanConfidenceIncorrect mean predicted probability for incorrect predictions
 */
public record CalibrationMetrics(
        boolean available,
        String unavailabilityReason,
        int evaluableCount,
        Double brierScore,
        Double baselineBrierScore,
        Double expectedCalibrationError,
        List<CalibrationBucket> buckets,
        Double meanConfidenceCorrect,
        Double meanConfidenceIncorrect
) {

    public CalibrationMetrics {
        buckets = buckets != null ? List.copyOf(buckets) : List.of();
    }

    public static CalibrationMetrics unavailable(String reason) {
        return new CalibrationMetrics(
                false,
                reason,
                0,
                null,
                null,
                null,
                List.of(),
                null,
                null
        );
    }

    public Optional<String> getUnavailabilityReason() {
        return Optional.ofNullable(unavailabilityReason);
    }

    public Optional<Double> getBrierScore() {
        return Optional.ofNullable(brierScore);
    }

    public Optional<Double> getBaselineBrierScore() {
        return Optional.ofNullable(baselineBrierScore);
    }

    public Optional<Double> getExpectedCalibrationError() {
        return Optional.ofNullable(expectedCalibrationError);
    }

    public Optional<Double> getMeanConfidenceCorrect() {
        return Optional.ofNullable(meanConfidenceCorrect);
    }

    public Optional<Double> getMeanConfidenceIncorrect() {
        return Optional.ofNullable(meanConfidenceIncorrect);
    }
}
