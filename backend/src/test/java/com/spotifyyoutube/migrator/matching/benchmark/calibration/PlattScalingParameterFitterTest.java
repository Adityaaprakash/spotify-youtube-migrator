package com.spotifyyoutube.migrator.matching.benchmark.calibration;

import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDatasetSplit;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkExpectedOutcome;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkTrackCategory;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkErrorCategory;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkExampleResult;
import com.spotifyyoutube.migrator.matching.benchmark.runner.EvaluationExclusionReason;
import com.spotifyyoutube.migrator.matching.engine.MatchConfidence;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PlattScalingParameterFitter")
class PlattScalingParameterFitterTest {

    @Nested
    @DisplayName("Input validation")
    class ValidationTests {

        private final PlattScalingParameterFitter fitter = new PlattScalingParameterFitter();

        @Test
        @DisplayName("should reject null inputs")
        void shouldRejectNullInputs() {
            assertThatThrownBy(() -> fitter.fit(null, "v1", "VAL"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> fitter.fit(List.of(), null, "VAL"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> fitter.fit(List.of(), "v1", null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should reject fewer than 4 observations")
        void shouldRejectTooFewObservations() {
            List<CalibrationObservation> list = List.of(
                    CalibrationObservation.of("1", 0.9, true),
                    CalibrationObservation.of("2", 0.4, false),
                    CalibrationObservation.of("3", 0.8, true)
            );
            assertThatThrownBy(() -> fitter.fit(list, "v1", "VAL"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("At least 4 observations required");
        }

        @Test
        @DisplayName("should reject all positive or all negative observations")
        void shouldRejectUnbalancedData() {
            List<CalibrationObservation> allPos = List.of(
                    CalibrationObservation.of("1", 0.9, true),
                    CalibrationObservation.of("2", 0.8, true),
                    CalibrationObservation.of("3", 0.85, true),
                    CalibrationObservation.of("4", 0.95, true)
            );
            assertThatThrownBy(() -> fitter.fit(allPos, "v1", "VAL"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least 1 negative");

            List<CalibrationObservation> allNeg = List.of(
                    CalibrationObservation.of("1", 0.3, false),
                    CalibrationObservation.of("2", 0.4, false),
                    CalibrationObservation.of("3", 0.5, false),
                    CalibrationObservation.of("4", 0.2, false)
            );
            assertThatThrownBy(() -> fitter.fit(allNeg, "v1", "VAL"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least 1 positive");
        }

        @Test
        @DisplayName("should reject near-zero variance scores")
        void shouldRejectZeroVarianceScores() {
            List<CalibrationObservation> zeroVar = List.of(
                    CalibrationObservation.of("1", 0.70, true),
                    CalibrationObservation.of("2", 0.70, true),
                    CalibrationObservation.of("3", 0.70, false),
                    CalibrationObservation.of("4", 0.70, false)
            );
            assertThatThrownBy(() -> fitter.fit(zeroVar, "v1", "VAL"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("near-zero variance");
        }
    }

    @Nested
    @DisplayName("Optimization and fitting")
    class FittingTests {

        @Test
        @DisplayName("should successfully fit parameters on separable data with positive slope")
        void shouldFitSeparableData() {
            PlattScalingParameterFitter fitter = new PlattScalingParameterFitter();

            List<CalibrationObservation> observations = new ArrayList<>();
            // Positive observations (high scores)
            observations.add(CalibrationObservation.of("p1", 0.88, true));
            observations.add(CalibrationObservation.of("p2", 0.92, true));
            observations.add(CalibrationObservation.of("p3", 0.95, true));
            observations.add(CalibrationObservation.of("p4", 0.96, true));
            observations.add(CalibrationObservation.of("p5", 0.85, true));

            // Negative observations (low scores)
            observations.add(CalibrationObservation.of("n1", 0.35, false));
            observations.add(CalibrationObservation.of("n2", 0.42, false));
            observations.add(CalibrationObservation.of("n3", 0.50, false));

            PlattScalingModel model = fitter.fit(observations, "v1.0-test", "VALIDATION");

            assertThat(model).isNotNull();
            assertThat(model.slopeA()).isGreaterThan(0.0);
            assertThat(model.interceptB()).isLessThan(0.0);
            assertThat(model.sampleCount()).isEqualTo(8);
            assertThat(model.positiveCount()).isEqualTo(5);
            assertThat(model.negativeCount()).isEqualTo(3);

            // Brier score should show reduction compared to raw score baseline
            assertThat(model.brierScore()).isLessThan(model.baselineBrierScore());

            // High score should have high predicted probability, low score low probability
            assertThat(model.predictProbability(0.95)).isGreaterThan(0.80);
            assertThat(model.predictProbability(0.35)).isLessThan(0.20);
        }

        @Test
        @DisplayName("should fit reliably with extreme scores near boundaries 0.0 and 1.0")
        void shouldFitExtremeBoundaryScores() {
            PlattScalingParameterFitter fitter = new PlattScalingParameterFitter();

            // Under Platt (1999) regularization, target probabilities are t+ = (N+ + 1)/(N+ + 2)
            // and t- = 1/(N- + 2). To achieve t+ > 0.90 and t- < 0.10, sample counts must satisfy
            // N+ >= 9 and N- >= 9. Here we provide N+ = 10, N- = 10 extreme boundary scores.
            List<CalibrationObservation> observations = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                observations.add(CalibrationObservation.of("p" + i, 0.990 + (i * 0.0009), true));
                observations.add(CalibrationObservation.of("n" + i, 0.001 + (i * 0.0009), false));
            }

            PlattScalingModel model = fitter.fit(observations, "v1.0-extreme", "VALIDATION");

            assertThat(model).isNotNull();
            assertThat(model.slopeA()).isGreaterThan(0.0);
            assertThat(model.predictProbability(0.999)).isGreaterThan(0.90);
            assertThat(model.predictProbability(0.001)).isLessThan(0.10);
            assertThat(Double.isFinite(model.logLikelihood())).isTrue();
            assertThat(Double.isFinite(model.brierScore())).isTrue();
        }

        @Test
        @DisplayName("should attenuate small-sample probabilities according to Platt regularization theorem")
        void shouldAttenuateSmallSampleProbabilities() {
            PlattScalingParameterFitter fitter = new PlattScalingParameterFitter();

            // With N+ = 2, N- = 2, Platt regularized targets are:
            // t+ = (2 + 1) / (2 + 2) = 0.75, t- = 1 / (2 + 2) = 0.25
            List<CalibrationObservation> smallSample = List.of(
                    CalibrationObservation.of("p1", 0.999, true),
                    CalibrationObservation.of("p2", 0.995, true),
                    CalibrationObservation.of("n1", 0.001, false),
                    CalibrationObservation.of("n2", 0.005, false)
            );

            PlattScalingModel model = fitter.fit(smallSample, "v1.0-small", "VALIDATION");

            assertThat(model).isNotNull();
            assertThat(model.slopeA()).isGreaterThan(0.0);
            // Optimal predictions converge to regularized targets t+ = 0.75 and t- = 0.25
            assertThat(model.predictProbability(0.999))
                    .isCloseTo(0.75, org.assertj.core.data.Offset.offset(0.02));
            assertThat(model.predictProbability(0.001))
                    .isCloseTo(0.25, org.assertj.core.data.Offset.offset(0.02));
        }

        @Test
        @DisplayName("should achieve sharp extreme probabilities when regularization is disabled")
        void shouldAchieveSharpProbabilitiesWithoutRegularization() {
            PlattScalingParameterFitter unregularizedFitter = new PlattScalingParameterFitter(false, 100, 1e-7);

            List<CalibrationObservation> smallSample = List.of(
                    CalibrationObservation.of("p1", 0.999, true),
                    CalibrationObservation.of("p2", 0.995, true),
                    CalibrationObservation.of("n1", 0.001, false),
                    CalibrationObservation.of("n2", 0.005, false)
            );

            PlattScalingModel model = unregularizedFitter.fit(smallSample, "v1.0-unreg", "VALIDATION");

            assertThat(model).isNotNull();
            assertThat(model.slopeA()).isGreaterThan(0.0);
            assertThat(model.predictProbability(0.999)).isGreaterThan(0.90);
            assertThat(model.predictProbability(0.001)).isLessThan(0.10);
        }

        @Test
        @DisplayName("should fit directly from BenchmarkExampleResult list")
        void shouldFitFromExampleResults() {
            PlattScalingParameterFitter fitter = new PlattScalingParameterFitter();

            List<BenchmarkExampleResult> results = List.of(
                    createTestResult("ex-1", true, 0.91, true),
                    createTestResult("ex-2", true, 0.87, true),
                    createTestResult("ex-3", true, 0.94, true),
                    createTestResult("ex-4", false, 0.40, false)
            );

            PlattScalingModel model = fitter.fitFromResults(results, "v1.0-from-results", "VALIDATION");

            assertThat(model).isNotNull();
            assertThat(model.sampleCount()).isEqualTo(4);
            assertThat(model.positiveCount()).isEqualTo(3);
            assertThat(model.negativeCount()).isEqualTo(1);
            assertThat(model.slopeA()).isGreaterThan(0.0);
        }

        private BenchmarkExampleResult createTestResult(
                String id,
                boolean isMatch,
                Double score,
                boolean isCorrect
        ) {
            return new BenchmarkExampleResult(
                    id,
                    isMatch ? BenchmarkExpectedOutcome.MATCH : BenchmarkExpectedOutcome.NO_MATCH,
                    isMatch ? "vid-" + id : null,
                    isCorrect ? "vid-" + id : (isMatch ? "wrongVid" : null),
                    isMatch ? MatchDecision.MATCH : MatchDecision.NO_MATCH,
                    isMatch ? MatchConfidence.GOOD : MatchConfidence.POOR,
                    score,
                    null,
                    List.of(),
                    1,
                    isMatch,
                    isCorrect,
                    true,
                    EvaluationExclusionReason.NONE,
                    BenchmarkTrackCategory.STANDARD_POPULAR,
                    BenchmarkDatasetSplit.VALIDATION,
                    10L,
                    isCorrect ? (isMatch ? BenchmarkErrorCategory.CORRECT_MATCH : BenchmarkErrorCategory.CORRECT_NO_MATCH)
                            : BenchmarkErrorCategory.WRONG_TARGET_SELECTED,
                    null
            );
        }
    }
}
