package com.spotifyyoutube.migrator.matching.engine.calibration;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable value object encapsulating the outcome of a match probability calibration attempt.
 * <p>
 * Distinguishes an empirical probability estimate in {@code [0.0, 1.0]} from heuristic similarity scores,
 * while honestly distinguishing a mathematically configured transformation from an empirically validated model.
 *
 * @param probability computed probability in {@code [0.0, 1.0]}, or null if calibration is unavailable
 * @param available whether a probability transformation was available and computed
 * @param validated whether the probability is backed by a validated empirical calibration model
 * @param calibrationMethod descriptive name of the calibration method (e.g. "PLATT_SCALING", "NONE")
 * @param modelVersion version or identifier of the calibration model parameters, or null if uncalibrated
 */
public record CalibrationResult(
        Double probability,
        boolean available,
        boolean validated,
        String calibrationMethod,
        String modelVersion
) {

    public CalibrationResult {
        calibrationMethod = calibrationMethod != null ? calibrationMethod : "NONE";
        modelVersion = modelVersion != null ? modelVersion : "UNVALIDATED";
        if (available && probability == null) {
            throw new IllegalArgumentException("Probability must not be null when calibration is available");
        }
        if (validated && !available) {
            throw new IllegalArgumentException("Calibration cannot be validated without an available probability");
        }
        if (probability != null) {
            if (Double.isNaN(probability) || Double.isInfinite(probability)) {
                throw new IllegalArgumentException("Probability must be a finite number: " + probability);
            }
            if (probability < 0.0 || probability > 1.0) {
                throw new IllegalArgumentException("Probability must be in [0.0, 1.0]: " + probability);
            }
        }
    }

    /**
     * Creates an unavailable calibration result representing the baseline state before empirical training.
     *
     * @param reason diagnostic explanation for why calibration is unavailable
     * @return unavailable calibration result
     */
    public static CalibrationResult unavailable(String reason) {
        Objects.requireNonNull(reason, "Reason must not be null");
        return new CalibrationResult(null, false, false, "NONE", "UNVALIDATED");
    }

    /**
     * Creates a configured transformation result that is not yet empirically validated.
     *
     * @param probability estimated probability in {@code [0.0, 1.0]}
     * @param method name of the calibration method applied
     * @param modelVersion identifier or timestamp of the configured model parameters
     * @return configured unvalidated result
     */
    public static CalibrationResult configured(Double probability, String method, String modelVersion) {
        return calibrated(probability, false, method, modelVersion);
    }

    /**
     * Creates an empirically validated calibration result supported by labeled benchmark data.
     *
     * @param probability estimated true-match probability in {@code [0.0, 1.0]}
     * @param method name of the calibration method applied
     * @param modelVersion identifier or timestamp of the validated model parameters
     * @return validated calibration result
     */
    public static CalibrationResult validated(Double probability, String method, String modelVersion) {
        return calibrated(probability, true, method, modelVersion);
    }

    /**
     * Creates a probability result with explicit empirical validation status.
     *
     * @param probability estimated probability in {@code [0.0, 1.0]}
     * @param validated whether this result is backed by an empirically validated model
     * @param method name of the calibration method applied
     * @param modelVersion identifier or timestamp of the model parameters
     * @return calibration result
     */
    public static CalibrationResult calibrated(
            Double probability,
            boolean validated,
            String method,
            String modelVersion
    ) {
        Objects.requireNonNull(probability, "Probability must not be null");
        if (Double.isNaN(probability) || Double.isInfinite(probability)) {
            throw new IllegalArgumentException("Probability must be a finite number: " + probability);
        }
        if (probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException("Probability must be in [0.0, 1.0]: " + probability);
        }
        Objects.requireNonNull(method, "Calibration method must not be null");
        if (method.isBlank()) {
            throw new IllegalArgumentException("Calibration method must not be blank");
        }
        Objects.requireNonNull(modelVersion, "Model version must not be null");
        if (modelVersion.isBlank()) {
            throw new IllegalArgumentException("Model version must not be blank");
        }

        double rounded = Math.round(probability * 10000.0) / 10000.0;
        return new CalibrationResult(rounded, true, validated, method, modelVersion);
    }

    /**
     * Backward-compatible factory creating a configured calibration result.
     *
     * @param probability estimated probability in {@code [0.0, 1.0]}
     * @param method name of the calibration method applied
     * @param modelVersion identifier or timestamp of the model parameters
     * @return configured calibration result
     */
    public static CalibrationResult calibrated(Double probability, String method, String modelVersion) {
        return configured(probability, method, modelVersion);
    }

    /**
     * Indicates whether a probability transformation was available and computed.
     *
     * @return true if probability is present, false otherwise
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Indicates whether this calibration result is backed by an empirically validated model.
     *
     * @return true if empirically validated, false if unvalidated or unavailable
     */
    public boolean isValidated() {
        return validated;
    }

    /**
     * Returns the calibrated probability wrapped in an {@link Optional} only if empirically validated.
     *
     * @return optional containing the validated probability, empty if unvalidated or unavailable
     */
    public Optional<Double> calibratedProbability() {
        return (available && validated) ? Optional.of(probability) : Optional.empty();
    }

    /**
     * Returns the computed probability wrapped in an {@link Optional} regardless of validation status.
     *
     * @return optional containing the computed probability if available, empty otherwise
     */
    public Optional<Double> configuredProbability() {
        return available ? Optional.ofNullable(probability) : Optional.empty();
    }

    /**
     * Alias for {@link #configuredProbability()}.
     *
     * @return optional containing the probability if available, empty otherwise
     */
    public Optional<Double> getProbability() {
        return configuredProbability();
    }
}
