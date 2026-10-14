package com.spotifyyoutube.migrator.matching.benchmark.calibration;

import java.util.List;
import java.util.Objects;

/**
 * Immutable report containing results of decision threshold optimization across raw score and
 * calibrated probability domains.
 *
 * @param scorePoints sweep results over composite similarity score thresholds
 * @param probabilityPoints sweep results over calibrated probability thresholds
 * @param optimalScorePoint optimal threshold point on raw scores for the target precision
 * @param optimalProbabilityPoint optimal threshold point on calibrated probabilities for the target precision
 * @param targetPrecision target precision constraint (e.g. 0.95 or 0.98)
 * @param evaluationSplit split name used for optimization (e.g. "VALIDATION")
 */
public record ThresholdOptimizationReport(
        List<ThresholdEvaluationPoint> scorePoints,
        List<ThresholdEvaluationPoint> probabilityPoints,
        ThresholdEvaluationPoint optimalScorePoint,
        ThresholdEvaluationPoint optimalProbabilityPoint,
        double targetPrecision,
        String evaluationSplit
) {

    public ThresholdOptimizationReport {
        Objects.requireNonNull(scorePoints, "scorePoints must not be null");
        Objects.requireNonNull(probabilityPoints, "probabilityPoints must not be null");
        Objects.requireNonNull(optimalScorePoint, "optimalScorePoint must not be null");
        Objects.requireNonNull(optimalProbabilityPoint, "optimalProbabilityPoint must not be null");
        Objects.requireNonNull(evaluationSplit, "evaluationSplit must not be null");
        if (evaluationSplit.isBlank()) {
            throw new IllegalArgumentException("evaluationSplit must not be blank");
        }
        if (Double.isNaN(targetPrecision) || targetPrecision <= 0.0 || targetPrecision > 1.0) {
            throw new IllegalArgumentException("targetPrecision must be in (0.0, 1.0]: " + targetPrecision);
        }
        scorePoints = List.copyOf(scorePoints);
        probabilityPoints = List.copyOf(probabilityPoints);
    }
}
