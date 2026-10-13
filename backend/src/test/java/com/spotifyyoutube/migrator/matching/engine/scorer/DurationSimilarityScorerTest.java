package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.CandidateVersionEvidence;
import com.spotifyyoutube.migrator.matching.engine.feature.OfficialSignalEvidence;
import com.spotifyyoutube.migrator.matching.engine.feature.SourceTrackFeatures;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedArtistCredit;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.TokenizedTitle;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DurationSimilarityScorer Tests (Phase 7E)")
class DurationSimilarityScorerTest {

    private DurationSimilarityScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new DefaultDurationSimilarityScorer();
    }

    private SourceTrackFeatures createSourceWithDuration(Duration duration) {
        Long durationMs = duration != null ? duration.toMillis() : null;
        NormalizedTrackMetadata metadata = new NormalizedTrackMetadata(
                RawTrackMetadata.of("Song Title", "Artist", "Album", durationMs),
                "song title",
                new NormalizedArtistCredit("artist", List.of()),
                List.of("artist"),
                List.of(),
                "album",
                duration,
                new TokenizedTitle("Song Title", "Song Title", false, Set.of(), null, null, Set.of(), List.of(), List.of(), List.of())
        );
        return new SourceTrackFeatures(
                "src-1", "song title", "Song Title", "artist",
                List.of("artist"), List.of(),
                "album", duration, durationMs,
                true, duration != null, true,
                Set.of(), true, false, false, false, metadata
        );
    }

    private CandidateTrackFeatures createCandidateWithDuration(Duration duration) {
        Long durationMs = duration != null ? duration.toMillis() : null;
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                "cand-1", "Song Title", "Artist", "Description", "https://thumb.jpg", "2021-01-01", duration
        );
        return new CandidateTrackFeatures(
                "cand-1", "Song Title", "song title", "Song Title",
                "Artist", "artist",
                duration, durationMs, duration != null, true, "Description", "2021-01-01",
                CandidateVersionEvidence.empty(), OfficialSignalEvidence.none(), candidate
        );
    }

    @Nested
    @DisplayName("Exact and Tolerance Zone Matches")
    class ExactAndToleranceMatches {

        @Test
        @DisplayName("should award 1.0 for exact duration match")
        void shouldAwardOneForExactDurationMatch() {
            SourceTrackFeatures source = createSourceWithDuration(Duration.ofMillis(200000));
            CandidateTrackFeatures candidate = createCandidateWithDuration(Duration.ofMillis(200000));

            Double score = scorer.score(source, candidate);

            assertThat(score).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should award 1.0 for small offset within 2.0s tolerance window")
        void shouldAwardOneWithinToleranceWindow() {
            // 1.5s difference (1500ms)
            Double score = scorer.score(Duration.ofSeconds(200), Duration.ofMillis(201500));
            assertThat(score).isEqualTo(1.0);

            // Exactly 2.0s boundary
            Double boundaryScore = scorer.score(Duration.ofSeconds(200), Duration.ofSeconds(202));
            assertThat(boundaryScore).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should award 1.0 when candidate is shorter within 2.0s tolerance window")
        void shouldAwardOneWhenCandidateShorterWithinTolerance() {
            Double score = scorer.score(Duration.ofSeconds(200), Duration.ofSeconds(199));
            assertThat(score).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Piecewise Continuous Decay and Boundaries")
    class DecayAndBoundaries {

        @Test
        @DisplayName("should linearly decay between 2s and 10s offsets (score >= 0.85)")
        void shouldDecayInSmallOffsetRange() {
            // Delta = 5.0s -> 1.0 - (3/8)*0.15 = 0.94375 -> 0.9438
            Double score5s = scorer.score(Duration.ofSeconds(200), Duration.ofSeconds(205));
            assertThat(score5s).isNotNull();
            assertThat(score5s).isGreaterThanOrEqualTo(0.90);
            assertThat(score5s).isLessThan(1.0);

            // Boundary at 10.0s -> exactly 0.85
            Double score10s = scorer.score(Duration.ofSeconds(200), Duration.ofSeconds(210));
            assertThat(score10s).isEqualTo(0.85);
        }

        @Test
        @DisplayName("should linearly decay between 10s and 30s offsets (score between 0.40 and 0.85)")
        void shouldDecayInModerateOffsetRange() {
            // Delta = 20.0s -> 0.85 - (10/20)*0.45 = 0.625
            Double score20s = scorer.score(Duration.ofSeconds(200), Duration.ofSeconds(220));
            assertThat(score20s).isNotNull();
            assertThat(score20s).isEqualTo(0.625);

            // Boundary at 30.0s -> exactly 0.40
            Double score30s = scorer.score(Duration.ofSeconds(200), Duration.ofSeconds(230));
            assertThat(score30s).isEqualTo(0.40);
        }

        @Test
        @DisplayName("should linearly decay between 30s and 60s offsets (score between 0.0 and 0.40)")
        void shouldDecayInLargeOffsetRange() {
            // Delta = 45.0s -> 0.40 - (15/30)*0.40 = 0.20
            Double score45s = scorer.score(Duration.ofSeconds(200), Duration.ofSeconds(245));
            assertThat(score45s).isNotNull();
            assertThat(score45s).isEqualTo(0.20);

            // Boundary at 60.0s -> exactly 0.0
            Double score60s = scorer.score(Duration.ofSeconds(200), Duration.ofSeconds(260));
            assertThat(score60s).isEqualTo(0.0);
        }

        @Test
        @DisplayName("should award 0.0 for offsets exceeding 60.0s")
        void shouldAwardZeroForExcessiveOffsets() {
            Double score75s = scorer.score(Duration.ofSeconds(200), Duration.ofSeconds(275));
            assertThat(score75s).isEqualTo(0.0);

            Double score10m = scorer.score(Duration.ofSeconds(200), Duration.ofSeconds(800));
            assertThat(score10m).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Monotonicity and Mathematical Invariants")
    class MonotonicityAndInvariants {

        @Test
        @DisplayName("score should be monotonic non-increasing as duration offset increases")
        void shouldBeMonotonicWithRespectToOffset() {
            long base = 200000L;
            double prevScore = 1.0;

            for (long offset = 0; offset <= 70000; offset += 2000) {
                Double currentScore = scorer.score(base, base + offset);
                assertThat(currentScore).isNotNull();
                assertThat(currentScore).isLessThanOrEqualTo(prevScore);
                assertThat(currentScore).isBetween(0.0, 1.0);
                prevScore = currentScore;
            }
        }

        @Test
        @DisplayName("scoring function should be symmetric with respect to source and candidate")
        void shouldBeSymmetric() {
            Double score1 = scorer.score(Duration.ofSeconds(200), Duration.ofSeconds(215));
            Double score2 = scorer.score(Duration.ofSeconds(215), Duration.ofSeconds(200));

            assertThat(score1).isEqualTo(score2);
        }
    }

    @Nested
    @DisplayName("Missing Metadata and Validation Policy")
    class MissingMetadataAndValidation {

        @Test
        @DisplayName("should return null when source duration is missing")
        void shouldReturnNullWhenSourceDurationMissing() {
            SourceTrackFeatures source = createSourceWithDuration(null);
            CandidateTrackFeatures candidate = createCandidateWithDuration(Duration.ofSeconds(200));

            Double score = scorer.score(source, candidate);

            assertThat(score).isNull();
        }

        @Test
        @DisplayName("should return null when candidate duration is missing")
        void shouldReturnNullWhenCandidateDurationMissing() {
            SourceTrackFeatures source = createSourceWithDuration(Duration.ofSeconds(200));
            CandidateTrackFeatures candidate = createCandidateWithDuration(null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isNull();
        }

        @Test
        @DisplayName("should return null when both durations are missing")
        void shouldReturnNullWhenBothDurationsMissing() {
            SourceTrackFeatures source = createSourceWithDuration(null);
            CandidateTrackFeatures candidate = createCandidateWithDuration(null);

            Double score = scorer.score(source, candidate);

            assertThat(score).isNull();
        }

        @Test
        @DisplayName("should reject null feature objects with NullPointerException")
        void shouldRejectNullFeatureObjects() {
            SourceTrackFeatures source = createSourceWithDuration(Duration.ofSeconds(200));
            CandidateTrackFeatures candidate = createCandidateWithDuration(Duration.ofSeconds(200));

            assertThatThrownBy(() -> scorer.score(null, candidate))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("SourceTrackFeatures must not be null");

            assertThatThrownBy(() -> scorer.score(source, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("CandidateTrackFeatures must not be null");
        }

        @Test
        @DisplayName("should reject negative durations with IllegalArgumentException")
        void shouldRejectNegativeDurations() {
            Duration negative = Duration.ofSeconds(-5);
            assertThatThrownBy(() -> scorer.score(negative, Duration.ofSeconds(200)))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> scorer.score(-1000L, 200000L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("scorer should be strictly deterministic across repeated invocations")
        void shouldBeDeterministic() {
            SourceTrackFeatures source = createSourceWithDuration(Duration.ofSeconds(200));
            CandidateTrackFeatures candidate = createCandidateWithDuration(Duration.ofSeconds(212));

            Double score1 = scorer.score(source, candidate);
            Double score2 = scorer.score(source, candidate);

            assertThat(score1).isEqualTo(score2);
        }
    }
}
