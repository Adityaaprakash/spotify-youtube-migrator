package com.spotifyyoutube.migrator.matching.engine.calibration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CalibrationResult Unit Tests")
class CalibrationResultTest {

    @Test
    @DisplayName("should create unavailable result with empty probability and reason")
    void shouldCreateUnavailableResult() {
        CalibrationResult result = CalibrationResult.unavailable("No labeled dataset available");

        assertThat(result.isAvailable()).isFalse();
        assertThat(result.isValidated()).isFalse();
        assertThat(result.calibratedProbability()).isEmpty();
        assertThat(result.configuredProbability()).isEmpty();
        assertThat(result.probability()).isNull();
        assertThat(result.calibrationMethod()).isEqualTo("NONE");
        assertThat(result.modelVersion()).isEqualTo("UNVALIDATED");
    }

    @Test
    @DisplayName("should reject null reason in unavailable factory")
    void shouldRejectNullReasonInUnavailable() {
        assertThatThrownBy(() -> CalibrationResult.unavailable(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Reason must not be null");
    }

    @Test
    @DisplayName("should create configured result that is available but unvalidated")
    void shouldCreateConfiguredUnvalidatedResult() {
        CalibrationResult result = CalibrationResult.configured(0.854321, "PLATT_SCALING", "platt-v1.0");

        assertThat(result.isAvailable()).isTrue();
        assertThat(result.isValidated()).isFalse();
        assertThat(result.configuredProbability()).contains(0.8543);
        assertThat(result.probability()).isEqualTo(0.8543);
        // Calibrated probability must be empty for unvalidated result
        assertThat(result.calibratedProbability()).isEmpty();
        assertThat(result.calibrationMethod()).isEqualTo("PLATT_SCALING");
        assertThat(result.modelVersion()).isEqualTo("platt-v1.0");
    }

    @Test
    @DisplayName("should create validated result with calibrated probability")
    void shouldCreateValidatedResult() {
        CalibrationResult result = CalibrationResult.validated(0.854321, "PLATT_SCALING", "platt-v1.0");

        assertThat(result.isAvailable()).isTrue();
        assertThat(result.isValidated()).isTrue();
        assertThat(result.calibratedProbability()).contains(0.8543);
        assertThat(result.probability()).isEqualTo(0.8543);
        assertThat(result.calibrationMethod()).isEqualTo("PLATT_SCALING");
        assertThat(result.modelVersion()).isEqualTo("platt-v1.0");
    }

    @Test
    @DisplayName("should clamp boundary probabilities exactly at 0.0 and 1.0")
    void shouldSupportBoundaryProbabilities() {
        CalibrationResult zero = CalibrationResult.validated(0.0, "PLATT", "v1");
        CalibrationResult one = CalibrationResult.validated(1.0, "PLATT", "v1");

        assertThat(zero.probability()).isEqualTo(0.0);
        assertThat(one.probability()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("should reject null probability in calibrated factory")
    void shouldRejectNullProbabilityInCalibrated() {
        assertThatThrownBy(() -> CalibrationResult.calibrated(null, true, "PLATT", "v1"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Probability must not be null");
    }

    @Test
    @DisplayName("should reject out-of-range probabilities")
    void shouldRejectOutOfRangeProbabilities() {
        assertThatThrownBy(() -> CalibrationResult.configured(-0.001, "PLATT", "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Probability must be in [0.0, 1.0]");

        assertThatThrownBy(() -> CalibrationResult.configured(1.001, "PLATT", "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Probability must be in [0.0, 1.0]");
    }

    @Test
    @DisplayName("should reject non-finite probabilities (NaN and Infinity)")
    void shouldRejectNonFiniteProbabilities() {
        assertThatThrownBy(() -> CalibrationResult.configured(Double.NaN, "PLATT", "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite number");

        assertThatThrownBy(() -> CalibrationResult.configured(Double.POSITIVE_INFINITY, "PLATT", "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite number");

        assertThatThrownBy(() -> CalibrationResult.configured(Double.NEGATIVE_INFINITY, "PLATT", "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite number");
    }

    @Test
    @DisplayName("should reject null or blank calibration method and model version")
    void shouldRejectNullOrBlankMetadata() {
        assertThatThrownBy(() -> CalibrationResult.configured(0.5, null, "v1"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> CalibrationResult.configured(0.5, "   ", "v1"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> CalibrationResult.configured(0.5, "PLATT", null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> CalibrationResult.configured(0.5, "PLATT", "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should reject validated status without available probability")
    void shouldRejectValidatedWithoutAvailable() {
        assertThatThrownBy(() -> new CalibrationResult(null, false, true, "PLATT", "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be validated without an available probability");
    }

    @Test
    @DisplayName("should verify equality and hashcode semantics for record")
    void shouldVerifyEquality() {
        CalibrationResult r1 = CalibrationResult.validated(0.85, "PLATT", "v1");
        CalibrationResult r2 = CalibrationResult.validated(0.85, "PLATT", "v1");
        CalibrationResult r3 = CalibrationResult.configured(0.85, "PLATT", "v1");

        assertThat(r1).isEqualTo(r2);
        assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
        assertThat(r1).isNotEqualTo(r3); // r1 is validated, r3 is unvalidated!
    }
}
