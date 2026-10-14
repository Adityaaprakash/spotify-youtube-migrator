package com.spotifyyoutube.migrator.matching.benchmark.calibration;

import com.spotifyyoutube.migrator.matching.engine.TrackMatchRequest;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchResult;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchingEngine;
import com.spotifyyoutube.migrator.matching.engine.calibration.DefaultMatchProbabilityCalibrator;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedArtistCredit;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.TokenizedTitle;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PlattScalingModel")
class PlattScalingModelTest {

    @Nested
    @DisplayName("Validation and invariants")
    class ValidationTests {

        @Test
        @DisplayName("should construct valid model")
        void shouldConstructValidModel() {
            PlattScalingModel model = new PlattScalingModel(
                    15.0,
                    -12.0,
                    "v1.0-test",
                    "VALIDATION",
                    36,
                    32,
                    4,
                    -10.5,
                    0.035,
                    0.090,
                    true
            );

            assertThat(model.slopeA()).isEqualTo(15.0);
            assertThat(model.interceptB()).isEqualTo(-12.0);
            assertThat(model.modelVersion()).isEqualTo("v1.0-test");
            assertThat(model.trainingSplit()).isEqualTo("VALIDATION");
            assertThat(model.sampleCount()).isEqualTo(36);
            assertThat(model.positiveCount()).isEqualTo(32);
            assertThat(model.negativeCount()).isEqualTo(4);
            assertThat(model.validated()).isTrue();
            assertThat(model.brierImprovement()).isCloseTo(0.055, org.assertj.core.data.Offset.offset(1e-6));
            assertThat(model.brierImprovementPercentage()).isGreaterThan(50.0);
        }

        @Test
        @DisplayName("should reject non-positive or invalid slopeA")
        void shouldRejectInvalidSlope() {
            assertThatThrownBy(() -> new PlattScalingModel(0.0, -5.0, "v1", "VAL", 10, 8, 2, -1.0, 0.1, 0.2, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("slopeA");

            assertThatThrownBy(() -> new PlattScalingModel(-1.5, -5.0, "v1", "VAL", 10, 8, 2, -1.0, 0.1, 0.2, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("slopeA");

            assertThatThrownBy(() -> new PlattScalingModel(Double.NaN, -5.0, "v1", "VAL", 10, 8, 2, -1.0, 0.1, 0.2, false))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject sample count mismatches or counts < 4")
        void shouldRejectSampleCountMismatches() {
            assertThatThrownBy(() -> new PlattScalingModel(10.0, -5.0, "v1", "VAL", 3, 2, 1, -1.0, 0.1, 0.2, false))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new PlattScalingModel(10.0, -5.0, "v1", "VAL", 10, 5, 4, -1.0, 0.1, 0.2, false))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject zero positive or zero negative counts")
        void shouldRejectMonolithicCounts() {
            assertThatThrownBy(() -> new PlattScalingModel(10.0, -5.0, "v1", "VAL", 10, 10, 0, -1.0, 0.1, 0.2, false))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new PlattScalingModel(10.0, -5.0, "v1", "VAL", 10, 0, 10, -1.0, 0.1, 0.2, false))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Probability prediction")
    class PredictionTests {

        @Test
        @DisplayName("should predict monotonic probabilities")
        void shouldPredictMonotonicProbabilities() {
            PlattScalingModel model = new PlattScalingModel(
                    15.0,
                    -12.0,
                    "v1.0",
                    "VALIDATION",
                    10,
                    8,
                    2,
                    -2.0,
                    0.05,
                    0.10,
                    false
            );

            double pLow = model.predictProbability(0.40);
            double pMid = model.predictProbability(0.80);
            double pHigh = model.predictProbability(0.95);

            assertThat(pLow).isLessThan(pMid);
            assertThat(pMid).isLessThan(pHigh);
            assertThat(pLow).isGreaterThanOrEqualTo(0.0);
            assertThat(pHigh).isLessThanOrEqualTo(1.0);
        }

        @Test
        @DisplayName("should handle extreme logit saturation safely")
        void shouldHandleSaturation() {
            // Near-boundary logits evaluate accurately without floating-point underflow
            PlattScalingModel model = new PlattScalingModel(
                    50.0,
                    -25.0,
                    "v1.0",
                    "VALIDATION",
                    10,
                    8,
                    2,
                    -2.0,
                    0.05,
                    0.10,
                    false
            );

            assertThat(model.predictProbability(0.0)).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(model.predictProbability(1.0)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));

            // Clamped extreme logits (|logit| >= 40.0) return exact saturation boundaries
            PlattScalingModel saturatedModel = new PlattScalingModel(
                    100.0,
                    -50.0,
                    "v1.0-saturated",
                    "VALIDATION",
                    10,
                    8,
                    2,
                    -2.0,
                    0.05,
                    0.10,
                    false
            );

            assertThat(saturatedModel.predictProbability(0.0)).isEqualTo(0.0);
            assertThat(saturatedModel.predictProbability(1.0)).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Factory and integration")
    class FactoryTests {

        @Test
        @DisplayName("should create validated and unvalidated calibrators")
        void shouldCreateCalibrators() {
            PlattScalingModel model = new PlattScalingModel(
                    12.0,
                    -10.0,
                    "v1.0",
                    "VAL",
                    10,
                    8,
                    2,
                    -2.0,
                    0.05,
                    0.10,
                    false
            );

            DefaultMatchProbabilityCalibrator validatedCal = model.toCalibrator(true);
            assertThat(validatedCal.isModelValidated()).isTrue();
            assertThat(validatedCal.modelVersion()).isEqualTo("v1.0");

            DefaultMatchProbabilityCalibrator unvalidatedCal = model.toCalibrator(false);
            assertThat(unvalidatedCal.isModelValidated()).isFalse();
        }

        @Test
        @DisplayName("should create matching engine wired with calibrated model")
        void shouldCreateMatchingEngine() {
            PlattScalingModel model = new PlattScalingModel(
                    15.0,
                    -12.0,
                    "v1.0-val",
                    "VALIDATION",
                    10,
                    8,
                    2,
                    -2.0,
                    0.04,
                    0.08,
                    true
            );

            TrackMatchingEngine engine = model.createMatchingEngine(true);
            assertThat(engine).isNotNull();

            NormalizedTrackMetadata source = new NormalizedTrackMetadata(
                    RawTrackMetadata.of("Blinding Lights", "The Weeknd", "After Hours", 200000L),
                    "blinding lights",
                    new NormalizedArtistCredit("the weeknd", List.of()),
                    List.of("the weeknd"),
                    List.of(),
                    "after hours",
                    Duration.ofMillis(200000L),
                    new TokenizedTitle(
                            "Blinding Lights",
                            "blinding lights",
                            false,
                            Set.of(),
                            null,
                            null,
                            Set.of(),
                            List.of(),
                            List.of(),
                            List.of()
                    )
            );

            DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                    "vid-123",
                    "The Weeknd - Blinding Lights (Official Video)",
                    "TheWeekndVEVO",
                    "Official music video",
                    "https://img.youtube.com/vi/vid-123/default.jpg",
                    "2020-01-21T00:00:00Z",
                    Duration.ofMillis(200000L)
            );

            TrackMatchRequest request = new TrackMatchRequest(
                    "src-1",
                    source,
                    List.of(candidate)
            );

            TrackMatchResult result = engine.matchTrack(request);
            assertThat(result).isNotNull();
            assertThat(result.hasSelectedCandidate()).isTrue();
            assertThat(result.selectedCandidate()).isNotNull();
            assertThat(result.calibratedProbability()).isNotNull();
            assertThat(result.calibratedProbability()).isGreaterThan(0.5);
        }
    }
}
