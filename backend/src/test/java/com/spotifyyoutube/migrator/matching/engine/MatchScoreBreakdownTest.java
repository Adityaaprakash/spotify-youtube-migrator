package com.spotifyyoutube.migrator.matching.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MatchScoreBreakdown Contract Tests")
class MatchScoreBreakdownTest {

    @Nested
    @DisplayName("Empty and Default Construction")
    class EmptyAndDefaultConstruction {

        @Test
        @DisplayName("empty() should produce all null component scores without defaulting to zero")
        void shouldProduceAllNullComponentScores() {
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.empty();

            assertThat(breakdown.titleScore()).isNull();
            assertThat(breakdown.artistScore()).isNull();
            assertThat(breakdown.durationScore()).isNull();
            assertThat(breakdown.albumScore()).isNull();
            assertThat(breakdown.officialSignalScore()).isNull();
            assertThat(breakdown.versionPenalty()).isNull();
            assertThat(breakdown.detailedPenalties()).isEmpty();

            assertThat(breakdown.isEmpty()).isTrue();
            assertThat(breakdown.hasTitleScore()).isFalse();
            assertThat(breakdown.hasArtistScore()).isFalse();
            assertThat(breakdown.hasDurationScore()).isFalse();
            assertThat(breakdown.hasAlbumScore()).isFalse();
            assertThat(breakdown.hasOfficialSignalScore()).isFalse();
            assertThat(breakdown.hasVersionPenalty()).isFalse();
        }

        @Test
        @DisplayName("Optional accessors should return Optional.empty() when scores are absent")
        void optionalAccessorsShouldReturnEmptyWhenAbsent() {
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.empty();

            assertThat(breakdown.getTitleScore()).isEmpty();
            assertThat(breakdown.getArtistScore()).isEmpty();
            assertThat(breakdown.getDurationScore()).isEmpty();
            assertThat(breakdown.getAlbumScore()).isEmpty();
            assertThat(breakdown.getOfficialSignalScore()).isEmpty();
            assertThat(breakdown.getVersionPenalty()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Builder Construction")
    class BuilderConstruction {

        @Test
        @DisplayName("builder should populate specified component scores and leave unpopulated scores as null")
        void builderShouldPopulateSpecifiedScores() {
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.builder()
                    .titleScore(0.95)
                    .artistScore(0.90)
                    .addPenalty("LIVE_MISMATCH", 0.15)
                    .versionPenalty(0.15)
                    .build();

            assertThat(breakdown.titleScore()).isEqualTo(0.95);
            assertThat(breakdown.artistScore()).isEqualTo(0.90);
            assertThat(breakdown.durationScore()).isNull();
            assertThat(breakdown.albumScore()).isNull();
            assertThat(breakdown.officialSignalScore()).isNull();
            assertThat(breakdown.versionPenalty()).isEqualTo(0.15);
            assertThat(breakdown.detailedPenalties()).containsEntry("LIVE_MISMATCH", 0.15);

            assertThat(breakdown.isEmpty()).isFalse();
            assertThat(breakdown.hasTitleScore()).isTrue();
            assertThat(breakdown.hasArtistScore()).isTrue();
            assertThat(breakdown.hasDurationScore()).isFalse();
            assertThat(breakdown.hasAlbumScore()).isFalse();
            assertThat(breakdown.hasOfficialSignalScore()).isFalse();
            assertThat(breakdown.hasVersionPenalty()).isTrue();

            assertThat(breakdown.getTitleScore()).contains(0.95);
            assertThat(breakdown.getArtistScore()).contains(0.90);
            assertThat(breakdown.getDurationScore()).isEmpty();
        }

        @Test
        @DisplayName("should populate all scores including official signal and album")
        void shouldPopulateAllScores() {
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.builder()
                    .titleScore(1.0)
                    .artistScore(1.0)
                    .durationScore(0.98)
                    .albumScore(0.85)
                    .officialSignalScore(1.0)
                    .versionPenalty(0.0)
                    .build();

            assertThat(breakdown.titleScore()).isEqualTo(1.0);
            assertThat(breakdown.artistScore()).isEqualTo(1.0);
            assertThat(breakdown.durationScore()).isEqualTo(0.98);
            assertThat(breakdown.albumScore()).isEqualTo(0.85);
            assertThat(breakdown.officialSignalScore()).isEqualTo(1.0);
            assertThat(breakdown.versionPenalty()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Immutability and Encapsulation")
    class ImmutabilityAndEncapsulation {

        @Test
        @DisplayName("detailedPenalties should be unmodifiable")
        void detailedPenaltiesShouldBeUnmodifiable() {
            Map<String, Double> mutablePenalties = new HashMap<>();
            mutablePenalties.put("COVER_MISMATCH", 0.25);

            MatchScoreBreakdown breakdown = new MatchScoreBreakdown(
                    0.8, 0.8, null, null, null, 0.25, mutablePenalties
            );

            // Mutating original map after construction does not alter breakdown
            mutablePenalties.put("EXTRA", 0.1);
            assertThat(breakdown.detailedPenalties()).hasSize(1);
            assertThat(breakdown.detailedPenalties()).doesNotContainKey("EXTRA");

            // Mutating through accessor throws UnsupportedOperationException
            assertThatThrownBy(() -> breakdown.detailedPenalties().put("NEW", 0.05))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("record equality and hashCode should operate as expected")
        void equalityAndHashCodeShouldOperate() {
            MatchScoreBreakdown b1 = MatchScoreBreakdown.builder()
                    .titleScore(0.9)
                    .artistScore(0.8)
                    .build();

            MatchScoreBreakdown b2 = MatchScoreBreakdown.builder()
                    .titleScore(0.9)
                    .artistScore(0.8)
                    .build();

            assertThat(b1).isEqualTo(b2);
            assertThat(b1.hashCode()).isEqualTo(b2.hashCode());
        }
    }
}
