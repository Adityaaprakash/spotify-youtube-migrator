package com.spotifyyoutube.migrator.matching.benchmark;

import com.spotifyyoutube.migrator.matching.benchmark.metric.DecisionPolicyMetrics;
import com.spotifyyoutube.migrator.matching.benchmark.metric.DecisionPolicyMetricsCalculator;
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

@DisplayName("DecisionPolicyMetricsCalculator Hand-Calculated Evaluation Tests")
class DecisionPolicyMetricsCalculatorTest {

    private final DecisionPolicyMetricsCalculator calculator = new DecisionPolicyMetricsCalculator();

    private BenchmarkExampleResult result(
            String id,
            BenchmarkExpectedOutcome outcome,
            String expectedVideoId,
            String predictedVideoId,
            MatchDecision decision,
            boolean eligible
    ) {
        boolean targetMatches = expectedVideoId != null && expectedVideoId.equals(predictedVideoId);
        BenchmarkErrorCategory errorCategory;

        if (outcome.isMatch()) {
            if (decision == MatchDecision.MATCH) {
                errorCategory = targetMatches
                        ? BenchmarkErrorCategory.CORRECT_MATCH
                        : BenchmarkErrorCategory.WRONG_TARGET_SELECTED;
            } else if (decision == MatchDecision.REVIEW_REQUIRED) {
                errorCategory = BenchmarkErrorCategory.REVIEW_REQUIRED;
            } else {
                errorCategory = BenchmarkErrorCategory.FALSE_NO_MATCH_FOR_POSITIVE;
            }
        } else {
            if (decision == MatchDecision.NO_MATCH) {
                errorCategory = BenchmarkErrorCategory.CORRECT_NO_MATCH;
            } else if (decision == MatchDecision.MATCH) {
                errorCategory = BenchmarkErrorCategory.FALSE_ACCEPTANCE_OF_NO_MATCH;
            } else {
                errorCategory = BenchmarkErrorCategory.REVIEW_REQUIRED;
            }
        }

        return new BenchmarkExampleResult(
                id,
                outcome,
                expectedVideoId,
                predictedVideoId,
                decision,
                MatchConfidence.GOOD,
                0.80,
                null,
                List.of(),
                2,
                expectedVideoId != null,
                targetMatches,
                eligible,
                eligible ? EvaluationExclusionReason.NONE : EvaluationExclusionReason.UNVERIFIED_STATUS,
                BenchmarkTrackCategory.STANDARD_POPULAR,
                BenchmarkDatasetSplit.DEV,
                20L,
                eligible ? errorCategory : BenchmarkErrorCategory.EXCLUDED_UNVERIFIED,
                null
        );
    }

    @Nested
    @DisplayName("Hand-Calculated 6-Way Confusion Matrix Verification")
    class HandCalculatedConfusionMatrixVerification {

        @Test
        @DisplayName("should match hand-calculated confusion counts and derived rates exactly")
        void shouldMatchHandCalculatedCountsAndRates() {
            List<BenchmarkExampleResult> results = List.of(
                    // 1 & 2: Correct Auto Accept (TP)
                    result("BM-01", BenchmarkExpectedOutcome.MATCH, "vid1", "vid1", MatchDecision.MATCH, true),
                    result("BM-02", BenchmarkExpectedOutcome.MATCH, "vid2", "vid2", MatchDecision.MATCH, true),

                    // 3: Wrong candidate accepted on positive match (FP)
                    result("BM-03", BenchmarkExpectedOutcome.MATCH, "vid3", "wrong_vid", MatchDecision.MATCH, true),

                    // 4: False acceptance on legitimate non-match (FP)
                    result("BM-04", BenchmarkExpectedOutcome.NO_MATCH, null, "any_vid", MatchDecision.MATCH, true),

                    // 5 & 6: Review required on positive matches
                    result("BM-05", BenchmarkExpectedOutcome.MATCH, "vid5", "vid5", MatchDecision.REVIEW_REQUIRED, true),
                    result("BM-06", BenchmarkExpectedOutcome.MATCH, "vid6", "wrong_vid", MatchDecision.REVIEW_REQUIRED, true),

                    // 7: Review required on legitimate non-match
                    result("BM-07", BenchmarkExpectedOutcome.NO_MATCH, null, "any_vid", MatchDecision.REVIEW_REQUIRED, true),

                    // 8 & 9: Correct NO_MATCH prediction (TN)
                    result("BM-08", BenchmarkExpectedOutcome.NO_MATCH, null, null, MatchDecision.NO_MATCH, true),
                    result("BM-09", BenchmarkExpectedOutcome.NO_MATCH, null, null, MatchDecision.NO_MATCH, true),

                    // 10: Incorrect rejection on positive match (FN)
                    result("BM-10", BenchmarkExpectedOutcome.MATCH, "vid10", null, MatchDecision.NO_MATCH, true),

                    // 11: Excluded unverified record (must NOT enter metrics)
                    result("BM-11", BenchmarkExpectedOutcome.MATCH, "vid11", "vid11", MatchDecision.MATCH, false)
            );

            DecisionPolicyMetrics m = calculator.calculate(results);

            assertThat(m.hasEvaluableData()).isTrue();
            assertThat(m.totalEligible()).isEqualTo(10);
            assertThat(m.totalExpectedMatch()).isEqualTo(6);
            assertThat(m.totalExpectedNoMatch()).isEqualTo(4);

            // Confusion components
            assertThat(m.correctAutoAccept()).isEqualTo(2);
            assertThat(m.wrongCandidateAccepted()).isEqualTo(1);
            assertThat(m.falseAcceptanceOfNoMatch()).isEqualTo(1);
            assertThat(m.incorrectAutoAccept()).isEqualTo(2);
            assertThat(m.totalAutoAccepted()).isEqualTo(4);

            assertThat(m.reviewRequiredPositive()).isEqualTo(2);
            assertThat(m.reviewRequiredNoMatch()).isEqualTo(1);
            assertThat(m.totalReviewRequired()).isEqualTo(3);

            assertThat(m.correctNoMatch()).isEqualTo(2);
            assertThat(m.incorrectRejection()).isEqualTo(1);
            assertThat(m.totalNoMatchPredicted()).isEqualTo(3);

            // Derived rates:
            // Precision = 2 / 4 = 0.50 (50%)
            assertThat(m.precision()).isEqualTo(0.50);
            assertThat(m.getPrecisionPercent()).contains(50.0);

            // Recall = 2 / 6 = 0.3333 (33.33%)
            assertThat(m.recall()).isEqualTo(0.3333);
            assertThat(m.getRecallPercent()).contains(33.33);

            // FPR = 1 / 4 = 0.25 (25%)
            assertThat(m.falsePositiveRate()).isEqualTo(0.25);
            assertThat(m.getFalsePositiveRatePercent()).contains(25.0);

            // FNR = 1 / 6 = 0.1667 (16.67%)
            assertThat(m.falseNegativeRate()).isEqualTo(0.1667);

            // NO_MATCH Precision = 2 / 3 = 0.6667
            assertThat(m.noMatchPrecision()).isEqualTo(0.6667);

            // NO_MATCH Recall = 2 / 4 = 0.50
            assertThat(m.noMatchRecall()).isEqualTo(0.50);

            // Review Rate = 3 / 10 = 0.30 (30%)
            assertThat(m.reviewRequiredRate()).isEqualTo(0.30);
            assertThat(m.getReviewRequiredRatePercent()).contains(30.0);

            // Auto-Accept Rate = 4 / 10 = 0.40 (40%)
            assertThat(m.automaticAcceptanceRate()).isEqualTo(0.40);
            assertThat(m.getAutomaticAcceptanceRatePercent()).contains(40.0);

            // Overall Accuracy = (2 + 2) / 10 = 0.40 (40%)
            assertThat(m.overallAccuracy()).isEqualTo(0.40);
        }
    }

    @Nested
    @DisplayName("Zero Denominator & Clean Undefined State")
    class ZeroDenominatorAndUndefinedState {

        @Test
        @DisplayName("empty result list should return clean null rates")
        void emptyResultsShouldReturnNullRates() {
            DecisionPolicyMetrics m = calculator.calculate(List.of());

            assertThat(m.hasEvaluableData()).isFalse();
            assertThat(m.totalEligible()).isEqualTo(0);
            assertThat(m.precision()).isNull();
            assertThat(m.recall()).isNull();
            assertThat(m.falsePositiveRate()).isNull();
            assertThat(m.falseNegativeRate()).isNull();
            assertThat(m.noMatchPrecision()).isNull();
            assertThat(m.noMatchRecall()).isNull();
            assertThat(m.reviewRequiredRate()).isNull();
            assertThat(m.automaticAcceptanceRate()).isNull();
            assertThat(m.overallAccuracy()).isNull();
        }

        @Test
        @DisplayName("dataset with zero automatic acceptances should yield undefined precision without throwing")
        void zeroAutoAcceptanceShouldYieldNullPrecision() {
            // Only reviews and rejections
            List<BenchmarkExampleResult> results = List.of(
                    result("BM-01", BenchmarkExpectedOutcome.MATCH, "vid1", "vid1", MatchDecision.REVIEW_REQUIRED, true),
                    result("BM-02", BenchmarkExpectedOutcome.NO_MATCH, null, null, MatchDecision.NO_MATCH, true)
            );

            DecisionPolicyMetrics m = calculator.calculate(results);

            assertThat(m.totalAutoAccepted()).isEqualTo(0);
            assertThat(m.precision()).isNull();
            assertThat(m.getPrecision()).isEmpty();
            assertThat(m.getPrecisionPercent()).isEmpty();
            assertThat(m.reviewRequiredRate()).isEqualTo(0.50);
            assertThat(m.automaticAcceptanceRate()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("dataset with zero NO_MATCH predictions should yield undefined NO_MATCH precision")
        void zeroNoMatchPredictionsShouldYieldNullPrecision() {
            List<BenchmarkExampleResult> results = List.of(
                    result("BM-01", BenchmarkExpectedOutcome.MATCH, "vid1", "vid1", MatchDecision.MATCH, true)
            );

            DecisionPolicyMetrics m = calculator.calculate(results);

            assertThat(m.totalNoMatchPredicted()).isEqualTo(0);
            assertThat(m.noMatchPrecision()).isNull();
            assertThat(m.getNoMatchPrecision()).isEmpty();
        }
    }
}
