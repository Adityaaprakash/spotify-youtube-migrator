package com.spotifyyoutube.migrator.matching.benchmark;

import com.spotifyyoutube.migrator.matching.benchmark.metric.BenchmarkErrorAnalysis;
import com.spotifyyoutube.migrator.matching.benchmark.metric.ErrorAnalysisCalculator;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkErrorCategory;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkExampleResult;
import com.spotifyyoutube.migrator.matching.benchmark.runner.EvaluationExclusionReason;
import com.spotifyyoutube.migrator.matching.engine.MatchConfidence;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ErrorAnalysisCalculator Aggregation & Distribution Tests")
class ErrorAnalysisCalculatorTest {

    private final ErrorAnalysisCalculator calculator = new ErrorAnalysisCalculator();

    private BenchmarkExampleResult result(
            String id,
            BenchmarkDatasetSplit split,
            BenchmarkTrackCategory category,
            BenchmarkExpectedOutcome outcome,
            MatchDecision decision,
            BenchmarkErrorCategory errorCategory
    ) {
        return new BenchmarkExampleResult(
                id,
                outcome,
                "targetVid",
                "predVid",
                decision,
                MatchConfidence.GOOD,
                0.80,
                null,
                List.of(),
                1,
                true,
                errorCategory == BenchmarkErrorCategory.CORRECT_MATCH,
                !errorCategory.isExcluded(),
                errorCategory.isExcluded() ? EvaluationExclusionReason.UNVERIFIED_STATUS : EvaluationExclusionReason.NONE,
                category,
                split,
                15L,
                errorCategory,
                null
        );
    }

    @Test
    @DisplayName("should accurately aggregate error classifications overall and across slices")
    void shouldAccuratelyAggregateClassifications() {
        List<BenchmarkExampleResult> results = List.of(
                result("BM-01", BenchmarkDatasetSplit.DEV, BenchmarkTrackCategory.STANDARD_POPULAR,
                        BenchmarkExpectedOutcome.MATCH, MatchDecision.MATCH, BenchmarkErrorCategory.CORRECT_MATCH),
                result("BM-02", BenchmarkDatasetSplit.DEV, BenchmarkTrackCategory.LIVE,
                        BenchmarkExpectedOutcome.MATCH, MatchDecision.REVIEW_REQUIRED, BenchmarkErrorCategory.VERSION_MISMATCH),
                result("BM-03", BenchmarkDatasetSplit.VALIDATION, BenchmarkTrackCategory.COVER,
                        BenchmarkExpectedOutcome.MATCH, MatchDecision.MATCH, BenchmarkErrorCategory.WRONG_TARGET_SELECTED),
                result("BM-04", BenchmarkDatasetSplit.DEV, BenchmarkTrackCategory.STANDARD_POPULAR,
                        BenchmarkExpectedOutcome.MATCH, MatchDecision.MATCH, BenchmarkErrorCategory.EXCLUDED_UNVERIFIED)
        );

        BenchmarkErrorAnalysis analysis = calculator.calculate(results);

        assertThat(analysis.totalEvaluated()).isEqualTo(4);

        // Overall counts
        assertThat(analysis.getOverallCount(BenchmarkErrorCategory.CORRECT_MATCH)).isEqualTo(1);
        assertThat(analysis.getOverallCount(BenchmarkErrorCategory.VERSION_MISMATCH)).isEqualTo(1);
        assertThat(analysis.getOverallCount(BenchmarkErrorCategory.WRONG_TARGET_SELECTED)).isEqualTo(1);
        assertThat(analysis.getOverallCount(BenchmarkErrorCategory.EXCLUDED_UNVERIFIED)).isEqualTo(1);
        assertThat(analysis.getOverallCount(BenchmarkErrorCategory.EXECUTION_FAILURE)).isEqualTo(0);

        // Sliced by Split
        assertThat(analysis.getCountBySplit(BenchmarkDatasetSplit.DEV, BenchmarkErrorCategory.CORRECT_MATCH)).isEqualTo(1);
        assertThat(analysis.getCountBySplit(BenchmarkDatasetSplit.DEV, BenchmarkErrorCategory.VERSION_MISMATCH)).isEqualTo(1);
        assertThat(analysis.getCountBySplit(BenchmarkDatasetSplit.DEV, BenchmarkErrorCategory.EXCLUDED_UNVERIFIED)).isEqualTo(1);
        assertThat(analysis.getCountBySplit(BenchmarkDatasetSplit.VALIDATION, BenchmarkErrorCategory.WRONG_TARGET_SELECTED)).isEqualTo(1);

        assertThat(analysis.hasEvaluableDataForSplit(BenchmarkDatasetSplit.DEV)).isTrue();
        assertThat(analysis.hasEvaluableDataForSplit(BenchmarkDatasetSplit.VALIDATION)).isTrue();
        assertThat(analysis.hasEvaluableDataForSplit(BenchmarkDatasetSplit.TEST)).isFalse();

        // Sliced by Category
        assertThat(analysis.getCountByCategory(BenchmarkTrackCategory.STANDARD_POPULAR, BenchmarkErrorCategory.CORRECT_MATCH)).isEqualTo(1);
        assertThat(analysis.getCountByCategory(BenchmarkTrackCategory.LIVE, BenchmarkErrorCategory.VERSION_MISMATCH)).isEqualTo(1);
        assertThat(analysis.hasEvaluableDataForCategory(BenchmarkTrackCategory.REMIX)).isFalse();
    }
}
