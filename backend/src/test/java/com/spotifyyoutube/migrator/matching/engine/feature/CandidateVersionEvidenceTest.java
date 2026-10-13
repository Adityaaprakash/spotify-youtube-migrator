package com.spotifyyoutube.migrator.matching.engine.feature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CandidateVersionEvidence Tests")
class CandidateVersionEvidenceTest {

    @Nested
    @DisplayName("Empty Construction")
    class EmptyConstruction {

        @Test
        @DisplayName("empty() should produce empty indicators and false indicators")
        void emptyShouldProduceEmptyIndicators() {
            CandidateVersionEvidence evidence = CandidateVersionEvidence.empty();

            assertThat(evidence.isEmpty()).isTrue();
            assertThat(evidence.indicators()).isEmpty();
            assertThat(evidence.rawTokens()).isEmpty();
            assertThat(evidence.unmatchedAnnotations()).isEmpty();
            assertThat(evidence.hasConflictingIndicators()).isFalse();
            assertThat(evidence.hasAmbiguity()).isFalse();

            assertThat(evidence.isCover()).isFalse();
            assertThat(evidence.isLive()).isFalse();
            assertThat(evidence.isRemix()).isFalse();
            assertThat(evidence.isKaraoke()).isFalse();
            assertThat(evidence.isReaction()).isFalse();
            assertThat(evidence.isNightcore()).isFalse();
            assertThat(evidence.isSpedUp()).isFalse();
            assertThat(evidence.isSlowed()).isFalse();
        }
    }

    @Nested
    @DisplayName("Indicator Helpers")
    class IndicatorHelpers {

        @Test
        @DisplayName("should accurately report active version indicators")
        void shouldAccuratelyReportIndicators() {
            Set<VersionIndicator> indicators = Set.of(
                    VersionIndicator.LIVE,
                    VersionIndicator.REMIX,
                    VersionIndicator.COVER
            );

            CandidateVersionEvidence evidence = new CandidateVersionEvidence(
                    indicators,
                    List.of("Live at Wembley", "Remix", "Cover"),
                    List.of(),
                    false,
                    true
            );

            assertThat(evidence.isLive()).isTrue();
            assertThat(evidence.isRemix()).isTrue();
            assertThat(evidence.isCover()).isTrue();
            assertThat(evidence.isKaraoke()).isFalse();
            assertThat(evidence.isReaction()).isFalse();
            assertThat(evidence.hasIndicator(VersionIndicator.LIVE)).isTrue();
            assertThat(evidence.hasIndicator(VersionIndicator.NIGHTCORE)).isFalse();
        }

        @Test
        @DisplayName("should flag conflicting indicators when present")
        void shouldFlagConflictingIndicators() {
            Set<VersionIndicator> indicators = Set.of(
                    VersionIndicator.SPED_UP,
                    VersionIndicator.SLOWED
            );

            CandidateVersionEvidence evidence = new CandidateVersionEvidence(
                    indicators,
                    List.of("Sped Up", "Slowed"),
                    List.of(),
                    true,
                    true
            );

            assertThat(evidence.hasConflictingIndicators()).isTrue();
            assertThat(evidence.isSpedUp()).isTrue();
            assertThat(evidence.isSlowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("collections should be unmodifiable")
        void collectionsShouldBeUnmodifiable() {
            Set<VersionIndicator> mutableSet = new LinkedHashSet<>();
            mutableSet.add(VersionIndicator.LIVE);

            List<String> mutableTokens = new ArrayList<>();
            mutableTokens.add("Live");

            CandidateVersionEvidence evidence = new CandidateVersionEvidence(
                    mutableSet,
                    mutableTokens,
                    List.of(),
                    false,
                    false
            );

            // Mutating outer structures has no effect
            mutableSet.add(VersionIndicator.REMIX);
            mutableTokens.add("Remix");
            assertThat(evidence.indicators()).hasSize(1);
            assertThat(evidence.rawTokens()).hasSize(1);

            // Mutating through accessors throws exception
            assertThatThrownBy(() -> evidence.indicators().add(VersionIndicator.COVER))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> evidence.rawTokens().add("Cover"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
