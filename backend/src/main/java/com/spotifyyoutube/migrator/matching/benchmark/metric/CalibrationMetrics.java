package com.spotifyyoutube.migrator.matching.benchmark.metric;

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
 * @param targetDefinition explicit definition of the forecasted event and ground-truth binary label
 * @param meanConfidenceCorrect mean predicted probability for correct matches
 * @param meanConfidenceIncorrect mean predicted probability for incorrect predictions
 */
public record CalibrationMetrics(
        boolean available,
        String unavailabilityReason,
        String targetDefinition,
        int evaluableCount,
        Double brierScore,
        Double baselineBrierScore,
        Double expectedCalibrationError,
        List<CalibrationBucket> buckets,
        Double meanConfidenceCorrect,
        Double meanConfidenceIncorrect
) {

    public static final String DEFAULT_TARGET_DEFINITION =
            "CANDIDATE_CORRECTNESS: P(evaluated candidate is the true target recording) against binary label y = 1 if (isMatch && candidateMatchesExpected) else 0";

    public CalibrationMetrics {
        targetDefinition = targetDefinition != null ? targetDefinition : DEFAULT_TARGET_DEFINITION;
        buckets = buckets != null ? List.copyOf(buckets) : List.of();
    }

    /**
     * Backward-compatible constructor defaulting targetDefinition to {@link #DEFAULT_TARGET_DEFINITION}.
     */
    public CalibrationMetrics(
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
        this(
                available,
                unavailabilityReason,
                DEFAULT_TARGET_DEFINITION,
                evaluableCount,
                brierScore,
                baselineBrierScore,
                expectedCalibrationError,
                buckets,
                meanConfidenceCorrect,
                meanConfidenceIncorrect
        );
    }

    public static CalibrationMetrics unavailable(String reason) {
        return new CalibrationMetrics(
                false,
                reason,
                DEFAULT_TARGET_DEFINITION,
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

    public Optional<String> getTargetDefinition() {
        return Optional.ofNullable(targetDefinition);
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
