package com.spotifyyoutube.migrator.matching.benchmark;

import com.spotifyyoutube.migrator.matching.benchmark.metric.HighConfidenceMetrics;
import com.spotifyyoutube.migrator.matching.benchmark.metric.HighConfidenceMetricsCalculator;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkErrorCategory;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkExampleResult;
import com.spotifyyoutube.migrator.matching.benchmark.runner.EvaluationExclusionReason;
import com.spotifyyoutube.migrator.matching.engine.MatchConfidence;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HighConfidenceMetricsCalculator Hand-Calculated Evaluation Tests")
class HighConfidenceMetricsCalculatorTest {

    private final HighConfidenceMetricsCalculator calculator = new HighConfidenceMetricsCalculator();

    private BenchmarkExampleResult resultWithProb(
            String id,
            BenchmarkExpectedOutcome outcome,
            String expectedVideoId,
            String predictedVideoId,
            MatchDecision decision,
            Double probability,
            boolean eligible
    ) {
        boolean targetMatches = expectedVideoId != null && expectedVideoId.equals(predictedVideoId);
        BenchmarkErrorCategory errorCategory = outcome.isMatch()
                ? (decision == MatchDecision.MATCH && targetMatches
                        ? BenchmarkErrorCategory.CORRECT_MATCH
                        : BenchmarkErrorCategory.WRONG_TARGET_SELECTED)
                : BenchmarkErrorCategory.FALSE_ACCEPTANCE_OF_NO_MATCH;

        return new BenchmarkExampleResult(
                id,
                outcome,
                expectedVideoId,
                predictedVideoId,
                decision,
                MatchConfidence.EXCELLENT,
                0.95,
                probability,
                List.of(),
                1,
                expectedVideoId != null,
                targetMatches,
                eligible,
                EvaluationExclusionReason.NONE,
                BenchmarkTrackCategory.STANDARD_POPULAR,
                BenchmarkDatasetSplit.DEV,
                10L,
                errorCategory,
                null
        );
    }

    @Nested
    @DisplayName("Hand-Calculated Metrics Verification")
    class HandCalculatedMetricsVerification {

        @Test
        @DisplayName("should compute accurate error rate across high-confidence predictions")
        void shouldComputeAccurateHighConfidenceRate() {
            List<BenchmarkExampleResult> results = List.of(
                    // 1: P = 0.95, Correct match
                    resultWithProb("BM-1", BenchmarkExpectedOutcome.MATCH, "vid1", "vid1", MatchDecision.MATCH, 0.95, true),
                    // 2: P = 0.92, Correct match
                    resultWithProb("BM-2", BenchmarkExpectedOutcome.MATCH, "vid2", "vid2", MatchDecision.MATCH, 0.92, true),
                    // 3: P = 0.91, Wrong target selected (incorrect)
                    resultWithProb("BM-3", BenchmarkExpectedOutcome.MATCH, "vid3", "wrong_vid", MatchDecision.MATCH, 0.91, true),
                    // 4: P = 0.85, Correct match (below 0.90 threshold, ignored)
                    resultWithProb("BM-4", BenchmarkExpectedOutcome.MATCH, "vid4", "vid4", MatchDecision.MATCH, 0.85, true),
                    // 5: P = 0.93, Decision is REVIEW_REQUIRED (not an accepted MATCH decision, ignored)
                    resultWithProb("BM-5", BenchmarkExpectedOutcome.MATCH, "vid5", "vid5", MatchDecision.REVIEW_REQUIRED, 0.93, true),
                    // 6: P = 0.94, Expected NO_MATCH, but MATCH predicted (incorrect)
                    resultWithProb("BM-6", BenchmarkExpectedOutcome.NO_MATCH, null, "any_vid", MatchDecision.MATCH, 0.94, true),
                    // 7: P = 0.99, Excluded record (ignored)
                    resultWithProb("BM-7", BenchmarkExpectedOutcome.MATCH, "vid7", "vid7", MatchDecision.MATCH, 0.99, false)
            );

            HighConfidenceMetrics m = calculator.calculate(results, 0.90);

            assertThat(m.available()).isTrue();
            assertThat(m.threshold()).isEqualTo(0.90);
            assertThat(m.highConfidenceAcceptedCount()).isEqualTo(4); // BM-1, BM-2, BM-3, BM-6
            assertThat(m.highConfidenceCorrectCount()).isEqualTo(2);  // BM-1, BM-2
            assertThat(m.highConfidenceIncorrectCount()).isEqualTo(2); // BM-3, BM-6
            assertThat(m.errorRate()).isEqualTo(0.50);
            assertThat(m.accuracy()).isEqualTo(0.50);
            assertThat(m.getErrorRatePercent()).contains(50.0);
            assertThat(m.getAccuracyPercent()).contains(50.0);
        }
    }

    @Nested
    @DisplayName("Uncalibrated Baseline & Zero-Denominator Safety")
    class UncalibratedBaselineAndEdgeCases {

        @Test
        @DisplayName("should honestly report unavailable when matching engine exposed null probabilities")
        void shouldReportUnavailableWhenProbabilitiesAreNull() {
            List<BenchmarkExampleResult> results = List.of(
                    resultWithProb("BM-1", BenchmarkExpectedOutcome.MATCH, "vid1", "vid1", MatchDecision.MATCH, null, true),
                    resultWithProb("BM-2", BenchmarkExpectedOutcome.NO_MATCH, null, null, MatchDecision.NO_MATCH, null, true)
            );

            HighConfidenceMetrics m = calculator.calculate(results);

            assertThat(m.available()).isFalse();
            assertThat(m.unavailabilityReason()).contains("DefaultMatchProbabilityCalibrator operates in uncalibrated baseline mode");
            assertThat(m.errorRate()).isNull();
            assertThat(m.getErrorRate()).isEmpty();
        }

        @Test
        @DisplayName("zero predictions exceeding threshold should yield null rate without throwing")
        void zeroExceedingThresholdShouldYieldNullRate() {
            List<BenchmarkExampleResult> results = List.of(
                    resultWithProb("BM-1", BenchmarkExpectedOutcome.MATCH, "vid1", "vid1", MatchDecision.MATCH, 0.75, true)
            );

            HighConfidenceMetrics m = calculator.calculate(results, 0.90);

            assertThat(m.available()).isTrue();
            assertThat(m.highConfidenceAcceptedCount()).isEqualTo(0);
            assertThat(m.highConfidenceIncorrectCount()).isEqualTo(0);
            assertThat(m.errorRate()).isNull();
            assertThat(m.getErrorRate()).isEmpty();
        }

        @Test
        @DisplayName("should reject invalid thresholds outside (0.0, 1.0] with IllegalArgumentException")
        void shouldRejectInvalidThresholds() {
            List<BenchmarkExampleResult> results = List.of(
                    resultWithProb("BM-1", BenchmarkExpectedOutcome.MATCH, "vid1", "vid1", MatchDecision.MATCH, 0.95, true)
            );

            assertThatThrownBy(() -> calculator.calculate(results, 0.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("threshold must be in (0.0, 1.0]");

            assertThatThrownBy(() -> calculator.calculate(results, -0.5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("threshold must be in (0.0, 1.0]");

            assertThatThrownBy(() -> calculator.calculate(results, 1.05))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("threshold must be in (0.0, 1.0]");

            assertThatThrownBy(() -> calculator.calculate(results, Double.NaN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("threshold must be in (0.0, 1.0]");
        }
    }
}
