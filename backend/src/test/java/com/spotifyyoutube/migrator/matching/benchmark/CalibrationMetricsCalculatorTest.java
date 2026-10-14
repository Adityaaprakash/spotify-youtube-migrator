package com.spotifyyoutube.migrator.matching.benchmark;

import com.spotifyyoutube.migrator.matching.benchmark.metric.CalibrationBucket;
import com.spotifyyoutube.migrator.matching.benchmark.metric.CalibrationMetrics;
import com.spotifyyoutube.migrator.matching.benchmark.metric.CalibrationMetricsCalculator;
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

@DisplayName("CalibrationMetricsCalculator Hand-Calculated Evaluation Tests")
class CalibrationMetricsCalculatorTest {

    private final CalibrationMetricsCalculator calculator = new CalibrationMetricsCalculator();

    private BenchmarkExampleResult result(
            String id,
            boolean isCorrectMatch,
            Double probability,
            boolean eligible
    ) {
        return new BenchmarkExampleResult(
                id,
                BenchmarkExpectedOutcome.MATCH,
                "targetVid",
                isCorrectMatch ? "targetVid" : "wrongVid",
                MatchDecision.MATCH,
                MatchConfidence.GOOD,
                0.85,
                probability,
                List.of(),
                1,
                true,
                isCorrectMatch,
                eligible,
                EvaluationExclusionReason.NONE,
                BenchmarkTrackCategory.STANDARD_POPULAR,
                BenchmarkDatasetSplit.DEV,
                15L,
                isCorrectMatch ? BenchmarkErrorCategory.CORRECT_MATCH : BenchmarkErrorCategory.WRONG_TARGET_SELECTED,
                null
        );
    }

    @Nested
    @DisplayName("Hand-Calculated Brier Score and ECE Verification")
    class HandCalculatedVerification {

        @Test
        @DisplayName("should match hand-calculated Brier score, baseline score, ECE, and bucket metrics")
        void shouldMatchHandCalculatedMetrics() {
            // 4 hand-calculated examples:
            // Ex 1: p = 0.90, correct (y = 1.0) -> sq error = (0.90 - 1.0)^2 = 0.0100
            // Ex 2: p = 0.80, correct (y = 1.0) -> sq error = (0.80 - 1.0)^2 = 0.0400
            // Ex 3: p = 0.70, incorrect (y = 0.0) -> sq error = (0.70 - 0.0)^2 = 0.4900
            // Ex 4: p = 0.20, incorrect (y = 0.0) -> sq error = (0.20 - 0.0)^2 = 0.0400
            // Ex 5: p = 0.99, excluded (must be ignored)
            List<BenchmarkExampleResult> results = List.of(
                    result("BM-1", true, 0.90, true),
                    result("BM-2", true, 0.80, true),
                    result("BM-3", false, 0.70, true),
                    result("BM-4", false, 0.20, true),
                    result("BM-5", true, 0.99, false)
            );

            // Using 5 bins: [0.0, 0.2), [0.2, 0.4), [0.4, 0.6), [0.6, 0.8), [0.8, 1.0]
            CalibrationMetrics m = calculator.calculate(results, 5);

            assertThat(m.available()).isTrue();
            assertThat(m.evaluableCount()).isEqualTo(4);

            // Brier score = (0.01 + 0.04 + 0.49 + 0.04) / 4 = 0.58 / 4 = 0.1450
            assertThat(m.brierScore()).isEqualTo(0.1450);

            // Base rate = 2 / 4 = 0.50 -> baseline Brier = 0.5 * 0.5 = 0.2500
            assertThat(m.baselineBrierScore()).isEqualTo(0.2500);

            // Mean confidences
            assertThat(m.meanConfidenceCorrect()).isEqualTo(0.8500);
            assertThat(m.meanConfidenceIncorrect()).isEqualTo(0.4500);

            // ECE for 5 bins:
            // Bin 0 [0.0, 0.2): count 0, empty
            // Bin 1 [0.2, 0.4): count 1 (Ex 4: p=0.20, y=0.0) -> |0 - 0.20| = 0.20
            // Bin 2 [0.4, 0.6): count 0, empty
            // Bin 3 [0.6, 0.8): count 1 (Ex 3: p=0.70, y=0.0) -> |0 - 0.70| = 0.70
            // Bin 4 [0.8, 1.0]: count 2 (Ex 1, 2: p=0.85, y=1.0) -> |1.0 - 0.85| = 0.15
            // ECE = (1/4)*0.20 + (1/4)*0.70 + (2/4)*0.15 = 0.05 + 0.175 + 0.075 = 0.3000
            assertThat(m.expectedCalibrationError()).isEqualTo(0.3000);

            List<CalibrationBucket> buckets = m.buckets();
            assertThat(buckets).hasSize(5);

            CalibrationBucket b0 = buckets.get(0);
            assertThat(b0.isEmpty()).isTrue();
            assertThat(b0.count()).isEqualTo(0);
            assertThat(b0.meanConfidence()).isNull();

            CalibrationBucket b1 = buckets.get(1);
            assertThat(b1.isEmpty()).isFalse();
            assertThat(b1.count()).isEqualTo(1);
            assertThat(b1.meanConfidence()).isEqualTo(0.2000);
            assertThat(b1.accuracy()).isEqualTo(0.0000);

            CalibrationBucket b4 = buckets.get(4);
            assertThat(b4.isEmpty()).isFalse();
            assertThat(b4.count()).isEqualTo(2);
            assertThat(b4.meanConfidence()).isEqualTo(0.8500);
            assertThat(b4.accuracy()).isEqualTo(1.0000);

            assertThat(m.targetDefinition()).isEqualTo(CalibrationMetrics.DEFAULT_TARGET_DEFINITION);
            assertThat(m.getTargetDefinition()).contains(CalibrationMetrics.DEFAULT_TARGET_DEFINITION);
        }

        @Test
        @DisplayName("should decouple candidate correctness from decision policy abstention (REVIEW_REQUIRED)")
        void shouldDecoupleCandidateCorrectnessFromDecisionPolicy() {
            // Case 1: candidate is correct (isCandidateCorrect == true), but decision is REVIEW_REQUIRED
            // p = 0.80, y = 1.0 -> squared error = (0.80 - 1.0)^2 = 0.0400
            BenchmarkExampleResult r1 = resultWithDecision("BM-1", true, MatchDecision.REVIEW_REQUIRED, 0.80);
            // Case 2: candidate is incorrect (wrong candidate), decision is REVIEW_REQUIRED
            // p = 0.20, y = 0.0 -> squared error = (0.20 - 0.0)^2 = 0.0400
            BenchmarkExampleResult r2 = resultWithDecision("BM-2", false, MatchDecision.REVIEW_REQUIRED, 0.20);

            CalibrationMetrics m = calculator.calculate(List.of(r1, r2), 5);

            assertThat(m.available()).isTrue();
            assertThat(m.evaluableCount()).isEqualTo(2);
            // Brier score = (0.04 + 0.04) / 2 = 0.0400
            assertThat(m.brierScore()).isEqualTo(0.0400);
            assertThat(m.targetDefinition()).contains("CANDIDATE_CORRECTNESS");
            assertThat(m.meanConfidenceCorrect()).isEqualTo(0.8000);
            assertThat(m.meanConfidenceIncorrect()).isEqualTo(0.2000);
        }
    }

    private BenchmarkExampleResult resultWithDecision(
            String id,
            boolean isCorrectMatch,
            MatchDecision decision,
            Double probability
    ) {
        return new BenchmarkExampleResult(
                id,
                BenchmarkExpectedOutcome.MATCH,
                "targetVid",
                isCorrectMatch ? "targetVid" : "wrongVid",
                decision,
                MatchConfidence.GOOD,
                0.85,
                probability,
                List.of(),
                1,
                true,
                isCorrectMatch,
                true,
                EvaluationExclusionReason.NONE,
                BenchmarkTrackCategory.STANDARD_POPULAR,
                BenchmarkDatasetSplit.DEV,
                15L,
                isCorrectMatch ? BenchmarkErrorCategory.CORRECT_MATCH : BenchmarkErrorCategory.WRONG_TARGET_SELECTED,
                null
        );
    }

    @Nested
    @DisplayName("Edge Cases & Missing Calibration Handling")
    class EdgeCasesAndMissingCalibrationHandling {

        @Test
        @DisplayName("should report unavailable when calibrated probabilities are absent")
        void shouldReportUnavailableWhenProbabilitiesAreNull() {
            List<BenchmarkExampleResult> results = List.of(
                    result("BM-1", true, null, true),
                    result("BM-2", false, null, true)
            );

            CalibrationMetrics m = calculator.calculate(results);

            assertThat(m.available()).isFalse();
            assertThat(m.brierScore()).isNull();
            assertThat(m.expectedCalibrationError()).isNull();
            assertThat(m.unavailabilityReason()).contains("DefaultMatchProbabilityCalibrator is uncalibrated baseline");
        }

        @Test
        @DisplayName("should reject bucket count less than 2")
        void shouldRejectInvalidBucketCount() {
            assertThatThrownBy(() -> calculator.calculate(List.of(), 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("numBuckets must be at least 2");
        }
    }
}
