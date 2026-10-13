package com.spotifyyoutube.migrator.matching.engine.calibration;

import com.spotifyyoutube.migrator.matching.engine.CandidateMatchEvaluation;
import com.spotifyyoutube.migrator.matching.engine.MatchScoreBreakdown;

import java.util.Objects;
import java.util.Optional;

/**
 * Strategy interface for calibrating raw heuristic composite similarity scores into
 * statistically grounded probabilities of true positive matches.
 * <p>
 * Distinguishes heuristic similarity scores in {@code [0.0, 1.0]} from empirical true-match
 * probabilities, honestly representing unavailable calibration when no validated empirical
 * dataset or model is active, and distinguishing configured transformations from validated models.
 */
public interface MatchProbabilityCalibrator {

    /**
     * Calibrates a composite score and score breakdown into a probability estimate.
     *
     * @param compositeScore composite similarity score in {@code [0.0, 1.0]}, must not be null
     * @param breakdown component score breakdown, may be null
     * @return calibration result containing probability (if available) and model metadata
     * @throws NullPointerException if compositeScore is null
     * @throws IllegalArgumentException if compositeScore is NaN, infinite, or out of {@code [0.0, 1.0]}
     */
    CalibrationResult calibrate(Double compositeScore, MatchScoreBreakdown breakdown);

    /**
     * Convenience method returning the validated calibrated probability as an {@link Optional}.
     *
     * @param compositeScore composite similarity score in {@code [0.0, 1.0]}, must not be null
     * @param breakdown component score breakdown, may be null
     * @return optional containing calibrated probability in {@code [0.0, 1.0]},
     *         or empty if unvalidated or unavailable
     */
    default Optional<Double> calibrateProbability(Double compositeScore, MatchScoreBreakdown breakdown) {
        return calibrate(compositeScore, breakdown).calibratedProbability();
    }

    /**
     * Calibrates the given candidate evaluation.
     *
     * @param evaluation candidate match evaluation, must not be null
     * @return calibration result containing probability and model metadata
     * @throws NullPointerException if evaluation is null
     */
    default CalibrationResult calibrate(CandidateMatchEvaluation evaluation) {
        Objects.requireNonNull(evaluation, "CandidateMatchEvaluation must not be null");
        return calibrate(evaluation.overallScore(), evaluation.scoreBreakdown());
    }

    /**
     * Indicates whether a calibration transformation (such as Platt scaling) is configured.
     *
     * @return true if a transformation is configured, false otherwise
     */
    boolean isConfigured();

    /**
     * Indicates whether this calibrator has an active, statistically validated empirical model
     * supported by labeled benchmark data.
     *
     * @return true if a validated model is active, false otherwise
     */
    boolean isModelValidated();

    /**
     * Returns the name of the calibration method (e.g. "PLATT_SCALING", "NONE").
     *
     * @return calibration method name
     */
    String calibrationMethod();

    /**
     * Returns the model version or parameter set identifier, or null if uncalibrated.
     *
     * @return model version identifier, or null
     */
    String modelVersion();
}
