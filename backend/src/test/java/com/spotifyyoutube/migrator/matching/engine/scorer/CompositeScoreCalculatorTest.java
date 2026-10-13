package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.MatchScoreBreakdown;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CompositeScoreCalculator Unit Tests")
class CompositeScoreCalculatorTest {

    private CompositeScoreCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new DefaultCompositeScoreCalculator();
    }

    @Nested
    @DisplayName("Complete Evidence and Standard Weights")
    class CompleteEvidence {

        @Test
        @DisplayName("should compute 1.0 for perfect score across all five dimensions")
        void shouldComputeOneForPerfectMatch() {
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.builder()
                    .titleScore(1.0)
                    .artistScore(1.0)
                    .durationScore(1.0)
                    .albumScore(1.0)
                    .officialSignalScore(1.0)
                    .versionPenalty(0.0)
                    .build();

            Double compositeScore = calculator.calculateCompositeScore(breakdown);

            assertThat(compositeScore).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should compute correct linear combination according to documented weights")
        void shouldComputeCorrectWeightedScore() {
            // Weights: Title 40%, Artist 30%, Duration 10%, Album 10%, Official 10%
            // 0.40 * 0.80 (0.32) + 0.30 * 0.90 (0.27) + 0.10 * 1.0 (0.10) + 0.10 * 0.50 (0.05) + 0.10 * 1.0 (0.10) = 0.84
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.builder()
                    .titleScore(0.80)
                    .artistScore(0.90)
                    .durationScore(1.0)
                    .albumScore(0.50)
                    .officialSignalScore(1.0)
                    .versionPenalty(0.0)
                    .build();

            Double compositeScore = calculator.calculateCompositeScore(breakdown);

            assertThat(compositeScore).isEqualTo(0.84);
        }
    }

    @Nested
    @DisplayName("Missing Evidence and Dynamic Re-normalization")
    class MissingEvidenceAndRenormalization {

        @Test
        @DisplayName("should dynamically re-normalize weights when secondary dimensions are absent")
        void shouldRenormalizeWhenAlbumAndOfficialAbsent() {
            // Active weights: Title (0.40) + Artist (0.30) + Duration (0.10) = 0.80
            // Title 1.0 (0.40) + Artist 1.0 (0.30) + Duration 1.0 (0.10) = 0.80 / 0.80 = 1.0
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.builder()
                    .titleScore(1.0)
                    .artistScore(1.0)
                    .durationScore(1.0)
                    .albumScore(null)
                    .officialSignalScore(null)
                    .versionPenalty(0.0)
                    .build();

            Double compositeScore = calculator.calculateCompositeScore(breakdown);

            assertThat(compositeScore).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should re-normalize proportionally across title and artist when other dimensions are absent")
        void shouldRenormalizeWhenOnlyTitleAndArtistPresent() {
            // Active weights: Title (0.40) + Artist (0.30) = 0.70
            // Title 0.90 (0.36) + Artist 0.80 (0.24) = 0.60
            // Base score = 0.60 / 0.70 = 0.85714... -> rounded to 4 decimals = 0.8571
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.builder()
                    .titleScore(0.90)
                    .artistScore(0.80)
                    .durationScore(null)
                    .albumScore(null)
                    .officialSignalScore(null)
                    .versionPenalty(0.0)
                    .build();

            Double compositeScore = calculator.calculateCompositeScore(breakdown);

            assertThat(compositeScore).isEqualTo(0.8571);
        }

        @Test
        @DisplayName("should return 0.0 when all component scores are absent")
        void shouldReturnZeroWhenAllAbsent() {
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.empty();

            Double compositeScore = calculator.calculateCompositeScore(breakdown);

            assertThat(compositeScore).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Version Penalty Deductions")
    class VersionPenaltyDeductions {

        @Test
        @DisplayName("should deduct version penalty once from base composite score")
        void shouldDeductVersionPenaltyOnce() {
            // Base score: 1.0. Cover penalty: 0.25 -> final score: 0.75
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.builder()
                    .titleScore(1.0)
                    .artistScore(1.0)
                    .durationScore(1.0)
                    .albumScore(1.0)
                    .officialSignalScore(1.0)
                    .versionPenalty(0.25)
                    .detailedPenalties(Map.of("COVER", 0.25))
                    .build();

            Double compositeScore = calculator.calculateCompositeScore(breakdown);

            assertThat(compositeScore).isEqualTo(0.75);
        }

        @Test
        @DisplayName("should clamp final score to 0.0 when penalty exceeds base score")
        void shouldClampScoreToZeroWhenPenaltyExceedsBase() {
            // Base score: 0.20. Reaction penalty: 0.30 -> final score clamped to 0.0
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.builder()
                    .titleScore(0.20)
                    .artistScore(0.20)
                    .durationScore(null)
                    .albumScore(null)
                    .officialSignalScore(null)
                    .versionPenalty(0.30)
                    .detailedPenalties(Map.of("REACTION", 0.30))
                    .build();

            Double compositeScore = calculator.calculateCompositeScore(breakdown);

            assertThat(compositeScore).isEqualTo(0.0);
        }

        @Test
        @DisplayName("should treat null version penalty as 0.0 deduction")
        void shouldTreatNullPenaltyAsZero() {
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.builder()
                    .titleScore(1.0)
                    .artistScore(1.0)
                    .versionPenalty(null)
                    .build();

            Double compositeScore = calculator.calculateCompositeScore(breakdown);

            assertThat(compositeScore).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Validation and Robustness")
    class ValidationAndRobustness {

        @Test
        @DisplayName("should reject null MatchScoreBreakdown with NullPointerException")
        void shouldRejectNullBreakdown() {
            assertThatThrownBy(() -> calculator.calculateCompositeScore(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should reject NaN score with IllegalArgumentException")
        void shouldRejectNaNScore() {
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.builder()
                    .titleScore(Double.NaN)
                    .build();

            assertThatThrownBy(() -> calculator.calculateCompositeScore(breakdown))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("titleScore must be a finite number");
        }

        @Test
        @DisplayName("should reject Infinite score with IllegalArgumentException")
        void shouldRejectInfiniteScore() {
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.builder()
                    .artistScore(Double.POSITIVE_INFINITY)
                    .build();

            assertThatThrownBy(() -> calculator.calculateCompositeScore(breakdown))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("artistScore must be a finite number");
        }

        @Test
        @DisplayName("should reject out-of-bounds score (< 0.0 or > 1.0) with IllegalArgumentException")
        void shouldRejectOutOfBoundsScore() {
            MatchScoreBreakdown under = MatchScoreBreakdown.builder().durationScore(-0.1).build();
            MatchScoreBreakdown over = MatchScoreBreakdown.builder().durationScore(1.1).build();

            assertThatThrownBy(() -> calculator.calculateCompositeScore(under))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> calculator.calculateCompositeScore(over))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject negative or invalid weight in constructor")
        void shouldRejectInvalidWeightsInConstructor() {
            assertThatThrownBy(() -> new DefaultCompositeScoreCalculator(-0.1, 0.3, 0.1, 0.1, 0.1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DefaultCompositeScoreCalculator(0.4, Double.NaN, 0.1, 0.1, 0.1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should produce deterministic result across repeated executions")
        void shouldBeDeterministic() {
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.builder()
                    .titleScore(0.85)
                    .artistScore(0.92)
                    .durationScore(0.95)
                    .albumScore(1.0)
                    .officialSignalScore(1.0)
                    .versionPenalty(0.15)
                    .build();

            Double score1 = calculator.calculateCompositeScore(breakdown);
            Double score2 = calculator.calculateCompositeScore(breakdown);

            assertThat(score1).isEqualTo(score2);
        }
    }

    @Nested
    @DisplayName("Pre-penalty Base Score Calculation")
    class BaseScoreCalculation {

        @Test
        @DisplayName("should calculate pre-penalty base score accurately without deducting penalty")
        void shouldCalculateBaseScoreWithoutDeductingPenalty() {
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.builder()
                    .titleScore(1.0)
                    .artistScore(1.0)
                    .durationScore(1.0)
                    .albumScore(1.0)
                    .officialSignalScore(1.0)
                    .versionPenalty(0.25)
                    .build();

            Double baseScore = calculator.calculateBaseScore(breakdown);
            Double compositeScore = calculator.calculateCompositeScore(breakdown);

            assertThat(baseScore).isEqualTo(1.0);
            assertThat(compositeScore).isEqualTo(0.75);
        }

        @Test
        @DisplayName("should re-normalize base score when secondary dimensions are missing")
        void shouldRenormalizeBaseScoreWhenSecondaryMissing() {
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.builder()
                    .titleScore(0.98)
                    .durationScore(1.0)
                    .versionPenalty(0.35)
                    .build();

            Double baseScore = calculator.calculateBaseScore(breakdown);

            assertThat(baseScore).isEqualTo(0.984);
        }

        @Test
        @DisplayName("should return 0.0 when all dimensions are missing")
        void shouldReturnZeroWhenAllDimensionsMissing() {
            Double baseScore = calculator.calculateBaseScore(MatchScoreBreakdown.empty());

            assertThat(baseScore).isEqualTo(0.0);
        }
    }
}
