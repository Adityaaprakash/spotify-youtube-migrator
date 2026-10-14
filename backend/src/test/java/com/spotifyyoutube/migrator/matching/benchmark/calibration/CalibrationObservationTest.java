package com.spotifyyoutube.migrator.matching.benchmark.calibration;

import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkExpectedOutcome;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkTrackCategory;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDatasetSplit;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CalibrationObservation")
class CalibrationObservationTest {

    @Nested
    @DisplayName("Constructor and validation")
    class ConstructorValidation {

        @Test
        @DisplayName("should construct valid observation without margin")
        void shouldConstructValidWithoutMargin() {
            CalibrationObservation obs = CalibrationObservation.of("ex-1", 0.85, true);

            assertThat(obs.exampleId()).isEqualTo("ex-1");
            assertThat(obs.score()).isEqualTo(0.85);
            assertThat(obs.isCandidateCorrect()).isTrue();
            assertThat(obs.margin()).isNull();
            assertThat(obs.getMargin()).isEmpty();
        }

        @Test
        @DisplayName("should construct valid observation with margin")
        void shouldConstructValidWithMargin() {
            CalibrationObservation obs = CalibrationObservation.of("ex-2", 0.92, true, 0.15);

            assertThat(obs.exampleId()).isEqualTo("ex-2");
            assertThat(obs.score()).isEqualTo(0.92);
            assertThat(obs.isCandidateCorrect()).isTrue();
            assertThat(obs.margin()).isEqualTo(0.15);
            assertThat(obs.getMargin()).contains(0.15);
        }

        @Test
        @DisplayName("should trim example ID whitespace")
        void shouldTrimExampleId() {
            CalibrationObservation obs = CalibrationObservation.of("  ex-3  ", 0.50, false);
            assertThat(obs.exampleId()).isEqualTo("ex-3");
        }

        @Test
        @DisplayName("should reject null or blank exampleId")
        void shouldRejectInvalidExampleId() {
            assertThatThrownBy(() -> CalibrationObservation.of(null, 0.5, true))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> CalibrationObservation.of("  ", 0.5, true))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject NaN, infinite, or out-of-range scores")
        void shouldRejectInvalidScores() {
            assertThatThrownBy(() -> CalibrationObservation.of("ex-1", Double.NaN, true))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> CalibrationObservation.of("ex-1", Double.POSITIVE_INFINITY, true))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> CalibrationObservation.of("ex-1", -0.01, true))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> CalibrationObservation.of("ex-1", 1.01, true))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject NaN or infinite margin")
        void shouldRejectInvalidMargin() {
            assertThatThrownBy(() -> CalibrationObservation.of("ex-1", 0.8, true, Double.NaN))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> CalibrationObservation.of("ex-1", 0.8, true, Double.NEGATIVE_INFINITY))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Factory from BenchmarkExampleResult")
    class FromResultTests {

        @Test
        @DisplayName("should correctly extract from evaluated benchmark result")
        void shouldExtractFromResult() {
            RankedCandidateSummary top = new RankedCandidateSummary(
                    1, "vid-1", "Song Title", "Artist", 0.88, null, MatchConfidence.EXCELLENT, true
            );
            RankedCandidateSummary runnerUp = new RankedCandidateSummary(
                    2, "vid-2", "Song Title (Remix)", "Artist", 0.66, null, MatchConfidence.GOOD, false
            );
            BenchmarkExampleResult result = new BenchmarkExampleResult(
                    "ex-100",
                    BenchmarkExpectedOutcome.MATCH,
                    "vid-1",
                    "vid-1",
                    MatchDecision.MATCH,
                    MatchConfidence.EXCELLENT,
                    0.88,
                    null,
                    List.of(top, runnerUp),
                    2,
                    true,
                    true,
                    true,
                    EvaluationExclusionReason.NONE,
                    BenchmarkTrackCategory.STANDARD_POPULAR,
                    BenchmarkDatasetSplit.VALIDATION,
                    10L,
                    BenchmarkErrorCategory.CORRECT_MATCH,
                    null
            );

            CalibrationObservation obs = CalibrationObservation.fromResult(result);

            assertThat(obs.exampleId()).isEqualTo("ex-100");
            assertThat(obs.score()).isEqualTo(0.88);
            assertThat(obs.isCandidateCorrect()).isTrue();
            assertThat(obs.margin()).isNotNull();
            assertThat(obs.margin()).isCloseTo(0.22, org.assertj.core.data.Offset.offset(1e-6));
        }

        @Test
        @DisplayName("should default score to 0.0 when predictedScore is absent")
        void shouldDefaultScoreWhenAbsent() {
            BenchmarkExampleResult result = new BenchmarkExampleResult(
                    "ex-101",
                    BenchmarkExpectedOutcome.NO_MATCH,
                    null,
                    null,
                    MatchDecision.NO_MATCH,
                    MatchConfidence.POOR,
                    null,
                    null,
                    List.of(),
                    0,
                    false,
                    false,
                    true,
                    EvaluationExclusionReason.NONE,
                    BenchmarkTrackCategory.STANDARD_POPULAR,
                    BenchmarkDatasetSplit.VALIDATION,
                    10L,
                    BenchmarkErrorCategory.CORRECT_NO_MATCH,
                    null
            );

            CalibrationObservation obs = CalibrationObservation.fromResult(result);

            assertThat(obs.exampleId()).isEqualTo("ex-101");
            assertThat(obs.score()).isEqualTo(0.0);
            assertThat(obs.isCandidateCorrect()).isFalse();
            assertThat(obs.margin()).isNull();
        }
    }
}
