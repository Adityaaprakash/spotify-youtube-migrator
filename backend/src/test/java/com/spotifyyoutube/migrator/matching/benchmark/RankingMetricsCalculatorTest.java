package com.spotifyyoutube.migrator.matching.benchmark;

import com.spotifyyoutube.migrator.matching.benchmark.metric.RankingMetrics;
import com.spotifyyoutube.migrator.matching.benchmark.metric.RankingMetricsCalculator;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkErrorCategory;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkExampleResult;
import com.spotifyyoutube.migrator.matching.benchmark.runner.EvaluationExclusionReason;
import com.spotifyyoutube.migrator.matching.benchmark.runner.RankedCandidateSummary;
import com.spotifyyoutube.migrator.matching.engine.MatchConfidence;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RankingMetricsCalculator Hand-Calculated Evaluation Tests")
class RankingMetricsCalculatorTest {

    private final RankingMetricsCalculator calculator = new RankingMetricsCalculator();

    private BenchmarkExampleResult createResult(
            String id,
            BenchmarkExpectedOutcome outcome,
            String expectedVideoId,
            boolean targetPresent,
            List<RankedCandidateSummary> candidates,
            boolean eligible
    ) {
        String predictedVideoId = candidates.isEmpty() ? null : candidates.get(0).videoId();
        boolean matches = expectedVideoId != null && expectedVideoId.equals(predictedVideoId);

        return new BenchmarkExampleResult(
                id,
                outcome,
                expectedVideoId,
                predictedVideoId,
                matches ? MatchDecision.MATCH : MatchDecision.NO_MATCH,
                MatchConfidence.GOOD,
                0.85,
                null,
                candidates,
                candidates.size(),
                targetPresent,
                matches,
                eligible,
                eligible ? EvaluationExclusionReason.NONE : EvaluationExclusionReason.UNVERIFIED_STATUS,
                BenchmarkTrackCategory.STANDARD_POPULAR,
                BenchmarkDatasetSplit.DEV,
                15L,
                matches ? BenchmarkErrorCategory.CORRECT_MATCH : BenchmarkErrorCategory.WRONG_TARGET_SELECTED,
                null
        );
    }

    private RankedCandidateSummary cand(int rank, String id, boolean isExpected) {
        return new RankedCandidateSummary(
                rank, id, "Title " + id, "Artist", 0.90 - (rank * 0.05), null, MatchConfidence.GOOD, isExpected
        );
    }

    @Nested
    @DisplayName("Hand-Calculated Metrics Verification")
    class HandCalculatedMetricsVerification {

        @Test
        @DisplayName("should accurately compute Top-1, Top-3, and recall against hand-calculated values")
        void shouldComputeAccurateRankingMetrics() {
            // Case 1: Target ranked 1st
            BenchmarkExampleResult r1 = createResult(
                    "BM-1", BenchmarkExpectedOutcome.MATCH, "target1", true,
                    List.of(cand(1, "target1", true), cand(2, "decoyA", false)), true
            );

            // Case 2: Target ranked 2nd (within top 3)
            BenchmarkExampleResult r2 = createResult(
                    "BM-2", BenchmarkExpectedOutcome.MATCH, "target2", true,
                    List.of(cand(1, "decoyB", false), cand(2, "target2", true), cand(3, "decoyC", false)), true
            );

            // Case 3: Target ranked 4th (outside top 3)
            BenchmarkExampleResult r3 = createResult(
                    "BM-3", BenchmarkExpectedOutcome.MATCH, "target3", true,
                    List.of(cand(1, "decoyD", false), cand(2, "decoyE", false),
                            cand(3, "decoyF", false), cand(4, "target3", true)), true
            );

            // Case 4: Target absent from pool (retrieval failure)
            BenchmarkExampleResult r4 = createResult(
                    "BM-4", BenchmarkExpectedOutcome.MATCH, "target4", false,
                    List.of(cand(1, "decoyG", false)), true
            );

            // Case 5: Legitimate NO_MATCH (must be excluded from ranking numerator & denominator)
            BenchmarkExampleResult r5 = createResult(
                    "BM-5", BenchmarkExpectedOutcome.NO_MATCH, null, false,
                    List.of(), true
            );

            // Case 6: Excluded positive example (must be ignored)
            BenchmarkExampleResult r6 = createResult(
                    "BM-6", BenchmarkExpectedOutcome.MATCH, "target6", true,
                    List.of(cand(1, "target6", true)), false
            );

            RankingMetrics metrics = calculator.calculate(List.of(r1, r2, r3, r4, r5, r6));

            assertThat(metrics.hasEvaluableData()).isTrue();
            // Denominators
            assertThat(metrics.eligiblePositiveMatches()).isEqualTo(4); // BM-1, 2, 3, 4
            assertThat(metrics.targetPresentCount()).isEqualTo(3);      // BM-1, 2, 3
            assertThat(metrics.targetAbsentCount()).isEqualTo(1);       // BM-4

            // Numerators
            assertThat(metrics.top1MatchCount()).isEqualTo(1);          // BM-1
            assertThat(metrics.top3MatchCount()).isEqualTo(2);          // BM-1, 2

            // Rates:
            // top1All = 1 / 4 = 0.25 (25.0%)
            assertThat(metrics.top1AccuracyAll()).isEqualTo(0.25);
            assertThat(metrics.getTop1AccuracyAllPercent()).contains(25.0);

            // top1Present = 1 / 3 = 0.3333 (33.33%)
            assertThat(metrics.top1AccuracyPresent()).isEqualTo(0.3333);
            assertThat(metrics.getTop1AccuracyPresentPercent()).contains(33.33);

            // top3All = 2 / 4 = 0.50 (50.0%)
            assertThat(metrics.top3AccuracyAll()).isEqualTo(0.50);
            assertThat(metrics.getTop3AccuracyAllPercent()).contains(50.0);

            // top3Present = 2 / 3 = 0.6667 (66.67%)
            assertThat(metrics.top3AccuracyPresent()).isEqualTo(0.6667);
            assertThat(metrics.getTop3AccuracyPresentPercent()).contains(66.67);

            // candidateRecallRate = 3 / 4 = 0.75 (75.0%)
            assertThat(metrics.candidateRecallRate()).isEqualTo(0.75);
            assertThat(metrics.getCandidateRecallRatePercent()).contains(75.0);
        }
    }

    @Nested
    @DisplayName("Zero Denominator & Edge Cases")
    class ZeroDenominatorAndEdgeCases {

        @Test
        @DisplayName("empty input list should yield clean unavailable state without throwing")
        void emptyInputShouldYieldUnavailable() {
            RankingMetrics metrics = calculator.calculate(List.of());

            assertThat(metrics.hasEvaluableData()).isFalse();
            assertThat(metrics.eligiblePositiveMatches()).isEqualTo(0);
            assertThat(metrics.top1AccuracyAll()).isNull();
            assertThat(metrics.top1AccuracyPresent()).isNull();
            assertThat(metrics.top3AccuracyAll()).isNull();
            assertThat(metrics.top3AccuracyPresent()).isNull();
            assertThat(metrics.candidateRecallRate()).isNull();
        }

        @Test
        @DisplayName("dataset containing only legitimate NO_MATCH examples should yield zero positive denominator")
        void onlyNoMatchShouldYieldZeroDenominator() {
            BenchmarkExampleResult r1 = createResult(
                    "BM-01", BenchmarkExpectedOutcome.NO_MATCH, null, false, List.of(), true
            );
            BenchmarkExampleResult r2 = createResult(
                    "BM-02", BenchmarkExpectedOutcome.NO_MATCH, null, false, List.of(), true
            );

            RankingMetrics metrics = calculator.calculate(List.of(r1, r2));

            assertThat(metrics.hasEvaluableData()).isFalse();
            assertThat(metrics.eligiblePositiveMatches()).isEqualTo(0);
            assertThat(metrics.getTop1AccuracyAll()).isEmpty();
            assertThat(metrics.getTop3AccuracyAll()).isEmpty();
        }

        @Test
        @DisplayName("dataset where all positive targets were absent should report 0.0 recall and null present accuracy")
        void allTargetsAbsentShouldReportZeroRecall() {
            BenchmarkExampleResult r = createResult(
                    "BM-01", BenchmarkExpectedOutcome.MATCH, "targetX", false,
                    List.of(cand(1, "decoyX", false)), true
            );

            RankingMetrics metrics = calculator.calculate(List.of(r));

            assertThat(metrics.hasEvaluableData()).isTrue();
            assertThat(metrics.eligiblePositiveMatches()).isEqualTo(1);
            assertThat(metrics.targetPresentCount()).isEqualTo(0);
            assertThat(metrics.targetAbsentCount()).isEqualTo(1);
            assertThat(metrics.top1AccuracyAll()).isEqualTo(0.0);
            assertThat(metrics.top1AccuracyPresent()).isNull();
            assertThat(metrics.candidateRecallRate()).isEqualTo(0.0);
        }
    }
}
