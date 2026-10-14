package com.spotifyyoutube.migrator.matching.benchmark.calibration;

import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkExampleResult;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable score-outcome observation pair for probability calibration fitting and threshold tuning.
 *
 * @param exampleId benchmark example identifier, must not be null or blank
 * @param score composite similarity score in {@code [0.0, 1.0]}
 * @param isCandidateCorrect whether the evaluated candidate corresponds to the true ground-truth target ($y_i \in \{0, 1\}$)
 * @param margin optional difference between top candidate score and runner-up score, or null if single candidate
 */
public record CalibrationObservation(
        String exampleId,
        double score,
        boolean isCandidateCorrect,
        Double margin
) {

    public CalibrationObservation {
        Objects.requireNonNull(exampleId, "exampleId must not be null");
        if (exampleId.isBlank()) {
            throw new IllegalArgumentException("exampleId must not be blank");
        }
        exampleId = exampleId.trim();
        if (Double.isNaN(score) || Double.isInfinite(score)) {
            throw new IllegalArgumentException("score must be finite: " + score);
        }
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be in [0.0, 1.0]: " + score);
        }
        if (margin != null && (Double.isNaN(margin) || Double.isInfinite(margin))) {
            throw new IllegalArgumentException("margin must be finite: " + margin);
        }
    }

    /**
     * Constructs an observation without margin.
     *
     * @param exampleId benchmark example ID
     * @param score composite similarity score
     * @param isCandidateCorrect true if candidate matches ground truth
     * @return constructed CalibrationObservation
     */
    public static CalibrationObservation of(String exampleId, double score, boolean isCandidateCorrect) {
        return new CalibrationObservation(exampleId, score, isCandidateCorrect, null);
    }

    /**
     * Constructs an observation with margin.
     *
     * @param exampleId benchmark example ID
     * @param score composite similarity score
     * @param isCandidateCorrect true if candidate matches ground truth
     * @param margin score difference to runner-up
     * @return constructed CalibrationObservation
     */
    public static CalibrationObservation of(
            String exampleId,
            double score,
            boolean isCandidateCorrect,
            Double margin
    ) {
        return new CalibrationObservation(exampleId, score, isCandidateCorrect, margin);
    }

    /**
     * Extracts a calibration observation from a benchmark example result.
     *
     * @param result benchmark example result, must not be null
     * @return extracted observation
     */
    public static CalibrationObservation fromResult(BenchmarkExampleResult result) {
        Objects.requireNonNull(result, "result must not be null");
        double score = result.predictedScore() != null ? result.predictedScore() : 0.0;
        Double margin = null;
        var topCandidates = result.topRankedCandidates();
        if (topCandidates != null && topCandidates.size() >= 2) {
            Double topScore = topCandidates.get(0).score();
            Double runnerUpScore = topCandidates.get(1).score();
            if (topScore != null && runnerUpScore != null) {
                margin = Math.max(0.0, topScore - runnerUpScore);
            }
        }
        return new CalibrationObservation(
                result.exampleId(),
                score,
                result.isCandidateCorrect(),
                margin
        );
    }

    public Optional<Double> getMargin() {
        return Optional.ofNullable(margin);
    }
}
