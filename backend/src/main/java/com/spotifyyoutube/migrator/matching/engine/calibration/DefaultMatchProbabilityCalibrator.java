package com.spotifyyoutube.migrator.matching.engine.calibration;

import com.spotifyyoutube.migrator.matching.engine.MatchScoreBreakdown;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Default implementation of {@link MatchProbabilityCalibrator}.
 * <p>
 * In the default baseline state (prior to Phase 8 empirical benchmark training),
 * this component honestly reports that no validated calibration model is available,
 * returning {@link CalibrationResult#unavailable(String)} with {@code probability = null}.
 * <p>
 * Also supports parameterized Platt scaling (logistic sigmoid transformation):
 * $$P(\text{match} \mid s) = \frac{1}{1 + e^{-(A \cdot s + B)}}$$
 * where $s$ is the composite similarity score in {@code [0.0, 1.0]}.
 * <p>
 * Parameterized transformations configured via {@link #withPlattScaling(double, double, String)}
 * are explicitly treated as configured but unvalidated ({@link #isModelValidated()} is false)
 * until supported by empirical benchmark evaluation.
 */
@Component
public class DefaultMatchProbabilityCalibrator implements MatchProbabilityCalibrator {

    public static final String METHOD_NONE = "NONE";
    public static final String METHOD_PLATT_SCALING = "PLATT_SCALING";

    private final boolean configured;
    private final boolean modelValidated;
    private final String method;
    private final String modelVersion;
    private final Double slopeA;
    private final Double interceptB;

    /**
     * Constructs an honest, uncalibrated instance representing the baseline state
     * before Phase 8 empirical labeled benchmark collection.
     */
    public DefaultMatchProbabilityCalibrator() {
        this.configured = false;
        this.modelValidated = false;
        this.method = METHOD_NONE;
        this.modelVersion = "UNVALIDATED";
        this.slopeA = null;
        this.interceptB = null;
    }

    /**
     * Constructs a calibrated instance with Platt scaling parameters and explicit validation status.
     *
     * @param slopeA logistic slope coefficient ($A$), must be non-zero and finite
     * @param interceptB logistic intercept ($B$), must be finite
     * @param modelVersion model version identifier (e.g. "v1.0-platt-20261013"), must not be blank
     * @param modelValidated whether this parameter set is empirically validated against ground-truth data
     */
    public DefaultMatchProbabilityCalibrator(
            double slopeA,
            double interceptB,
            String modelVersion,
            boolean modelValidated
    ) {
        validateFinite("slopeA", slopeA);
        if (slopeA == 0.0) {
            throw new IllegalArgumentException("Slope must not be zero");
        }
        validateFinite("interceptB", interceptB);
        Objects.requireNonNull(modelVersion, "modelVersion must not be null");
        if (modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion must not be blank");
        }
        this.configured = true;
        this.modelValidated = modelValidated;
        this.method = METHOD_PLATT_SCALING;
        this.modelVersion = modelVersion.trim();
        this.slopeA = slopeA;
        this.interceptB = interceptB;
    }

    /**
     * Constructs a configured but unvalidated Platt scaling instance.
     *
     * @param slopeA logistic slope coefficient ($A$), must be non-zero and finite
     * @param interceptB logistic intercept ($B$), must be finite
     * @param modelVersion model version identifier, must not be blank
     */
    public DefaultMatchProbabilityCalibrator(double slopeA, double interceptB, String modelVersion) {
        this(slopeA, interceptB, modelVersion, false);
    }

    /**
     * Static factory for creating a configured, unvalidated Platt-scaling calibrator instance.
     *
     * @param slopeA logistic slope coefficient ($A$), must be non-zero and finite
     * @param interceptB logistic intercept ($B$), must be finite
     * @param modelVersion model version identifier, must not be blank
     * @return configured unvalidated MatchProbabilityCalibrator instance
     */
    public static DefaultMatchProbabilityCalibrator withPlattScaling(
            double slopeA,
            double interceptB,
            String modelVersion
    ) {
        return new DefaultMatchProbabilityCalibrator(slopeA, interceptB, modelVersion, false);
    }

    /**
     * Static factory for creating an empirically validated Platt-scaling calibrator instance.
     *
     * @param slopeA logistic slope coefficient ($A$), must be non-zero and finite
     * @param interceptB logistic intercept ($B$), must be finite
     * @param modelVersion model version identifier, must not be blank
     * @return validated MatchProbabilityCalibrator instance
     */
    public static DefaultMatchProbabilityCalibrator withValidatedPlattScaling(
            double slopeA,
            double interceptB,
            String modelVersion
    ) {
        return new DefaultMatchProbabilityCalibrator(slopeA, interceptB, modelVersion, true);
    }

    @Override
    public CalibrationResult calibrate(Double compositeScore, MatchScoreBreakdown breakdown) {
        if (compositeScore == null) {
            throw new IllegalArgumentException("compositeScore must not be null");
        }
        validateScore("compositeScore", compositeScore);

        if (!configured) {
            return CalibrationResult.unavailable("No empirical calibration model configured");
        }

        // Numerically stable logistic sigmoid transformation: P = 1 / (1 + exp(-(A * s + B)))
        double logit = (slopeA * compositeScore) + interceptB;
        double probability;
        if (logit >= 40.0) {
            probability = 1.0;
        } else if (logit <= -40.0) {
            probability = 0.0;
        } else {
            probability = 1.0 / (1.0 + Math.exp(-logit));
        }

        // Clamp strictly to [0.0, 1.0]
        double clamped = Math.max(0.0, Math.min(1.0, probability));

        return modelValidated
                ? CalibrationResult.validated(clamped, method, modelVersion)
                : CalibrationResult.configured(clamped, method, modelVersion);
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public boolean isModelValidated() {
        return modelValidated;
    }

    @Override
    public String calibrationMethod() {
        return method;
    }

    @Override
    public String modelVersion() {
        return modelVersion;
    }

    private void validateScore(String name, Double value) {
        if (value.isNaN() || value.isInfinite() || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be finite and in [0.0, 1.0]: " + value);
        }
    }

    private void validateFinite(String name, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be a finite number: " + value);
        }
    }
}
