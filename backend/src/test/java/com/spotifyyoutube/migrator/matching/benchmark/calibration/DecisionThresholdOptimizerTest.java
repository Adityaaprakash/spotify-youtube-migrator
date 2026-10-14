package com.spotifyyoutube.migrator.matching.benchmark.calibration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DecisionThresholdOptimizer")
class DecisionThresholdOptimizerTest {

    private final DecisionThresholdOptimizer optimizer = new DecisionThresholdOptimizer();

    private final List<CalibrationObservation> testObservations = List.of(
            CalibrationObservation.of("p1", 0.95, true),
            CalibrationObservation.of("p2", 0.92, true),
            CalibrationObservation.of("p3", 0.88, true),
            CalibrationObservation.of("p4", 0.84, true),
            CalibrationObservation.of("p5", 0.78, true),
            CalibrationObservation.of("n1", 0.72, false),
            CalibrationObservation.of("n2", 0.65, false),
            CalibrationObservation.of("n3", 0.50, false),
            CalibrationObservation.of("n4", 0.40, false),
            CalibrationObservation.of("n5", 0.30, false)
    );

    private final PlattScalingModel testModel = new PlattScalingModel(
            15.0,
            -11.5,
            "v1.0",
            "VALIDATION",
            10,
            5,
            5,
            -3.0,
            0.04,
            0.12,
            true
    );

    @Nested
    @DisplayName("Sweeps and evaluation")
    class SweepTests {

        @Test
        @DisplayName("should sweep scores correctly across range")
        void shouldSweepScores() {
            List<ThresholdEvaluationPoint> points = optimizer.sweepScores(testObservations, 0.50, 0.90, 0.10);

            assertThat(points).hasSize(5); // 0.50, 0.60, 0.70, 0.80, 0.90
            for (ThresholdEvaluationPoint pt : points) {
                assertThat(pt.totalSamples()).isEqualTo(10);
                assertThat(pt.truePositives() + pt.falsePositives() + pt.trueNegatives() + pt.falseNegatives())
                        .isEqualTo(10);
                assertThat(pt.precision()).isBetween(0.0, 1.0);
                assertThat(pt.recall()).isBetween(0.0, 1.0);
            }

            // At threshold 0.90, only p1 (0.95) and p2 (0.92) pass -> TP = 2, FP = 0 -> Precision = 1.0
            ThresholdEvaluationPoint pt90 = points.getLast();
            assertThat(pt90.threshold()).isEqualTo(0.90);
            assertThat(pt90.truePositives()).isEqualTo(2);
            assertThat(pt90.falsePositives()).isEqualTo(0);
            assertThat(pt90.precision()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should sweep probabilities correctly using model")
        void shouldSweepProbabilities() {
            List<ThresholdEvaluationPoint> points = optimizer.sweepProbabilities(
                    testObservations,
                    testModel,
                    0.50,
                    0.90,
                    0.10
            );

            assertThat(points).hasSize(5);
            for (ThresholdEvaluationPoint pt : points) {
                assertThat(pt.totalSamples()).isEqualTo(10);
            }
        }
    }

    @Nested
    @DisplayName("Optimal operating point selection")
    class OptimalSelectionTests {

        @Test
        @DisplayName("should select optimal point meeting target precision with highest recall")
        void shouldSelectOptimalPoint() {
            List<ThresholdEvaluationPoint> points = optimizer.sweepScores(testObservations, 0.50, 0.90, 0.05);

            // Target 100% precision: highest recall with FP=0 is at threshold 0.75 (where TP=5, FP=0)
            ThresholdEvaluationPoint optimal = optimizer.findOptimalOperatingPoint(points, 1.00);

            assertThat(optimal).isNotNull();
            assertThat(optimal.precision()).isEqualTo(1.00);
            assertThat(optimal.falsePositives()).isEqualTo(0);
            assertThat(optimal.truePositives()).isGreaterThan(0);
        }

        @Test
        @DisplayName("should fall back gracefully to highest precision point if target cannot be met")
        void shouldFallbackIfTargetUnmet() {
            List<ThresholdEvaluationPoint> points = List.of(
                    ThresholdEvaluationPoint.of(0.70, 4, 2, 4, 0), // precision = 4/6 = 0.667
                    ThresholdEvaluationPoint.of(0.80, 3, 1, 5, 1)  // precision = 3/4 = 0.750
            );

            // Target precision 0.99 cannot be met
            ThresholdEvaluationPoint optimal = optimizer.findOptimalOperatingPoint(points, 0.99);

            assertThat(optimal.threshold()).isEqualTo(0.80);
            assertThat(optimal.precision()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("should reject empty points or invalid target precision")
        void shouldRejectInvalidParams() {
            assertThatThrownBy(() -> optimizer.findOptimalOperatingPoint(List.of(), 0.95))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> optimizer.findOptimalOperatingPoint(
                    List.of(ThresholdEvaluationPoint.of(0.5, 1, 0, 1, 0)), 0.0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Full optimization report")
    class ReportTests {

        @Test
        @DisplayName("should generate complete threshold optimization report")
        void shouldGenerateReport() {
            ThresholdOptimizationReport report = optimizer.optimize(
                    testObservations,
                    testModel,
                    0.95,
                    "VALIDATION"
            );

            assertThat(report).isNotNull();
            assertThat(report.evaluationSplit()).isEqualTo("VALIDATION");
            assertThat(report.targetPrecision()).isEqualTo(0.95);
            assertThat(report.optimalScorePoint()).isNotNull();
            assertThat(report.optimalProbabilityPoint()).isNotNull();
            assertThat(report.scorePoints()).isNotEmpty();
            assertThat(report.probabilityPoints()).isNotEmpty();
        }
    }
}
