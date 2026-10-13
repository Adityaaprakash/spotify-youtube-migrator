package com.spotifyyoutube.migrator.matching.engine.calibration;

import com.spotifyyoutube.migrator.matching.engine.CandidateMatchEvaluation;
import com.spotifyyoutube.migrator.matching.engine.MatchConfidence;
import com.spotifyyoutube.migrator.matching.engine.MatchScoreBreakdown;
import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.CandidateVersionEvidence;
import com.spotifyyoutube.migrator.matching.engine.feature.OfficialSignalEvidence;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DefaultMatchProbabilityCalibrator Unit Tests")
class DefaultMatchProbabilityCalibratorTest {

    @Nested
    @DisplayName("Uncalibrated Baseline (Default)")
    class UncalibratedBaseline {

        private final MatchProbabilityCalibrator calibrator = new DefaultMatchProbabilityCalibrator();

        @Test
        @DisplayName("should report unconfigured and unvalidated model status")
        void shouldReportUnvalidatedModelStatus() {
            assertThat(calibrator.isConfigured()).isFalse();
            assertThat(calibrator.isModelValidated()).isFalse();
            assertThat(calibrator.calibrationMethod()).isEqualTo("NONE");
            assertThat(calibrator.modelVersion()).isEqualTo("UNVALIDATED");
        }

        @Test
        @DisplayName("should return unavailable CalibrationResult for any valid score")
        void shouldReturnUnavailableResult() {
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.builder()
                    .titleScore(0.9)
                    .artistScore(0.8)
                    .build();

            CalibrationResult result = calibrator.calibrate(0.85, breakdown);

            assertThat(result.isAvailable()).isFalse();
            assertThat(result.isValidated()).isFalse();
            assertThat(result.calibratedProbability()).isEmpty();
            assertThat(result.configuredProbability()).isEmpty();
            assertThat(result.calibrationMethod()).isEqualTo("NONE");
            assertThat(result.modelVersion()).isEqualTo("UNVALIDATED");
        }

        @Test
        @DisplayName("should return empty optional from calibrateProbability")
        void shouldReturnEmptyOptionalFromCalibrateProbability() {
            Optional<Double> prob = calibrator.calibrateProbability(0.90, null);

            assertThat(prob).isEmpty();
        }

        @Test
        @DisplayName("should reject invalid score even when unvalidated")
        void shouldRejectInvalidScoreWhenUnvalidated() {
            assertThatThrownBy(() -> calibrator.calibrate(null, null))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> calibrator.calibrate(-0.1, null))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> calibrator.calibrate(1.1, null))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> calibrator.calibrate(Double.NaN, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Configured Unvalidated Mode (Platt Scaling)")
    class ConfiguredUnvalidatedMode {

        // Logit = 10.0 * s - 5.0. Configured but explicitly NOT empirically validated
        private final MatchProbabilityCalibrator calibrator =
                DefaultMatchProbabilityCalibrator.withPlattScaling(10.0, -5.0, "platt-v1.0");

        @Test
        @DisplayName("should report configured but unvalidated model status")
        void shouldReportConfiguredUnvalidatedStatus() {
            assertThat(calibrator.isConfigured()).isTrue();
            assertThat(calibrator.isModelValidated()).isFalse();
            assertThat(calibrator.calibrationMethod()).isEqualTo("PLATT_SCALING");
            assertThat(calibrator.modelVersion()).isEqualTo("platt-v1.0");
        }

        @Test
        @DisplayName("should return configured probability but empty calibrated probability")
        void shouldReturnConfiguredProbabilityWithoutValidatedClaim() {
            CalibrationResult result = calibrator.calibrate(0.50, null);

            assertThat(result.isAvailable()).isTrue();
            assertThat(result.isValidated()).isFalse();
            assertThat(result.configuredProbability()).contains(0.5000);
            assertThat(result.probability()).isEqualTo(0.5000);
            assertThat(result.calibratedProbability()).isEmpty();
        }

        @Test
        @DisplayName("should return empty optional from calibrateProbability when unvalidated")
        void shouldReturnEmptyOptionalFromCalibrateProbability() {
            Optional<Double> prob = calibrator.calibrateProbability(0.85, null);

            assertThat(prob).isEmpty();
        }
    }

    @Nested
    @DisplayName("Empirically Validated Mode (Validated Platt Scaling)")
    class ValidatedMode {

        // Logit = 10.0 * s - 5.0. Explicitly empirically validated
        private final MatchProbabilityCalibrator calibrator =
                DefaultMatchProbabilityCalibrator.withValidatedPlattScaling(10.0, -5.0, "platt-v1.0");

        @Test
        @DisplayName("should report validated model status and metadata")
        void shouldReportValidatedModelStatus() {
            assertThat(calibrator.isConfigured()).isTrue();
            assertThat(calibrator.isModelValidated()).isTrue();
            assertThat(calibrator.calibrationMethod()).isEqualTo("PLATT_SCALING");
            assertThat(calibrator.modelVersion()).isEqualTo("platt-v1.0");
        }

        @Test
        @DisplayName("should compute expected sigmoid probability at midpoint")
        void shouldComputeExpectedSigmoidAtMidpoint() {
            CalibrationResult result = calibrator.calibrate(0.50, null);

            assertThat(result.isAvailable()).isTrue();
            assertThat(result.isValidated()).isTrue();
            assertThat(result.calibratedProbability()).contains(0.5000);
            assertThat(result.calibrationMethod()).isEqualTo("PLATT_SCALING");
            assertThat(result.modelVersion()).isEqualTo("platt-v1.0");
        }

        @Test
        @DisplayName("should compute high probability for high score")
        void shouldComputeHighProbabilityForHighScore() {
            // At s = 1.0, logit = 5.0, prob = 1 / (1 + exp(-5)) = 0.9933
            CalibrationResult result = calibrator.calibrate(1.0, null);

            assertThat(result.isAvailable()).isTrue();
            assertThat(result.calibratedProbability().orElseThrow()).isGreaterThanOrEqualTo(0.99);
        }

        @Test
        @DisplayName("should compute low probability for low score")
        void shouldComputeLowProbabilityForLowScore() {
            // At s = 0.0, logit = -5.0, prob = 1 / (1 + exp(5)) = 0.0067
            CalibrationResult result = calibrator.calibrate(0.0, null);

            assertThat(result.isAvailable()).isTrue();
            assertThat(result.calibratedProbability().orElseThrow()).isLessThanOrEqualTo(0.01);
        }

        @Test
        @DisplayName("should be completely deterministic for identical inputs")
        void shouldBeDeterministic() {
            CalibrationResult r1 = calibrator.calibrate(0.77, null);
            CalibrationResult r2 = calibrator.calibrate(0.77, null);

            assertThat(r1).isEqualTo(r2);
            assertThat(r1.calibratedProbability()).isEqualTo(r2.calibratedProbability());
        }

        @Test
        @DisplayName("should remain numerically stable with extreme finite parameters")
        void shouldRemainNumericallyStableWithExtremeParameters() {
            MatchProbabilityCalibrator steep =
                    DefaultMatchProbabilityCalibrator.withValidatedPlattScaling(1000.0, -500.0, "steep-v1");

            // Extreme positive logit: s = 1.0 -> logit = 500.0 -> P = 1.0
            CalibrationResult high = steep.calibrate(1.0, null);
            assertThat(high.probability()).isEqualTo(1.0);

            // Extreme negative logit: s = 0.0 -> logit = -500.0 -> P = 0.0
            CalibrationResult low = steep.calibrate(0.0, null);
            assertThat(low.probability()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("should calibrate CandidateMatchEvaluation instance")
        void shouldCalibrateCandidateMatchEvaluation() {
            CandidateMatchEvaluation eval = createEvaluation(0.85);

            CalibrationResult result = calibrator.calibrate(eval);

            assertThat(result.isAvailable()).isTrue();
            assertThat(result.isValidated()).isTrue();
            assertThat(result.calibratedProbability()).isPresent();
            assertThat(result.calibratedProbability().orElseThrow()).isGreaterThan(0.90);
        }

        @Test
        @DisplayName("should reject null CandidateMatchEvaluation")
        void shouldRejectNullEvaluation() {
            assertThatThrownBy(() -> calibrator.calibrate((CandidateMatchEvaluation) null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should reject CandidateMatchEvaluation with null overallScore")
        void shouldRejectEvaluationWithNullScore() {
            CandidateMatchEvaluation eval = createEvaluation(null);

            assertThatThrownBy(() -> calibrator.calibrate(eval))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should validate constructor arguments")
        void shouldValidateConstructorArguments() {
            assertThatThrownBy(() -> DefaultMatchProbabilityCalibrator.withPlattScaling(0.0, -5.0, "v1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Slope must not be zero");

            assertThatThrownBy(() -> DefaultMatchProbabilityCalibrator.withPlattScaling(Double.NaN, -5.0, "v1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("slopeA must be a finite number");

            assertThatThrownBy(() ->
                    DefaultMatchProbabilityCalibrator.withPlattScaling(Double.POSITIVE_INFINITY, -5.0, "v1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("slopeA must be a finite number");

            assertThatThrownBy(() ->
                    DefaultMatchProbabilityCalibrator.withPlattScaling(Double.NEGATIVE_INFINITY, -5.0, "v1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("slopeA must be a finite number");

            assertThatThrownBy(() -> DefaultMatchProbabilityCalibrator.withPlattScaling(10.0, Double.NaN, "v1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("interceptB must be a finite number");

            assertThatThrownBy(() ->
                    DefaultMatchProbabilityCalibrator.withPlattScaling(10.0, Double.POSITIVE_INFINITY, "v1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("interceptB must be a finite number");

            assertThatThrownBy(() ->
                    DefaultMatchProbabilityCalibrator.withPlattScaling(10.0, Double.NEGATIVE_INFINITY, "v1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("interceptB must be a finite number");

            assertThatThrownBy(() -> DefaultMatchProbabilityCalibrator.withPlattScaling(10.0, -5.0, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("modelVersion must not be null");

            assertThatThrownBy(() -> DefaultMatchProbabilityCalibrator.withPlattScaling(10.0, -5.0, "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("modelVersion must not be blank");
        }
    }

    private CandidateMatchEvaluation createEvaluation(Double overallScore) {
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                "cand-1", "Track Title", "Artist", "Desc", "thumb", "2021-01-01", Duration.ofMinutes(3)
        );
        CandidateTrackFeatures features = new CandidateTrackFeatures(
                "cand-1", "Track Title", "track title", "Track Title", "Artist", "artist",
                Duration.ofMinutes(3), 180000L, true, true, "Desc", "2021-01-01",
                CandidateVersionEvidence.empty(), OfficialSignalEvidence.none(), candidate
        );
        MatchScoreBreakdown breakdown = MatchScoreBreakdown.builder()
                .titleScore(overallScore)
                .artistScore(1.0)
                .build();

        return CandidateMatchEvaluation.of(
                candidate,
                overallScore,
                MatchConfidence.GOOD,
                breakdown,
                List.of(),
                features
        );
    }
}
