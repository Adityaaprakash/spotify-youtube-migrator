package com.spotifyyoutube.migrator.matching.engine.ranking;

import com.spotifyyoutube.migrator.matching.engine.CandidateMatchEvaluation;
import com.spotifyyoutube.migrator.matching.engine.MatchConfidence;
import com.spotifyyoutube.migrator.matching.engine.MatchScoreBreakdown;
import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.CandidateVersionEvidence;
import com.spotifyyoutube.migrator.matching.engine.feature.OfficialSignalEvidence;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CandidateRankingEngine Unit Tests")
class CandidateRankingEngineTest {

    private CandidateRankingEngine rankingEngine;

    @BeforeEach
    void setUp() {
        rankingEngine = new DefaultCandidateRankingEngine();
    }

    @Nested
    @DisplayName("Basic Ranking Behavior")
    class BasicRanking {

        @Test
        @DisplayName("should handle empty list gracefully")
        void shouldHandleEmptyList() {
            List<CandidateMatchEvaluation> result = rankingEngine.rankCandidates(List.of());

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should handle single candidate")
        void shouldHandleSingleCandidate() {
            CandidateMatchEvaluation eval = createEvaluation("cand-1", "Song Title", 0.85, null);

            List<CandidateMatchEvaluation> result = rankingEngine.rankCandidates(List.of(eval));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).candidateId()).isEqualTo("cand-1");
        }

        @Test
        @DisplayName("should sort candidates descending by overall composite score")
        void shouldSortDescendingByOverallScore() {
            CandidateMatchEvaluation low = createEvaluation("cand-low", "Song", 0.60, null);
            CandidateMatchEvaluation high = createEvaluation("cand-high", "Song", 0.95, null);
            CandidateMatchEvaluation mid = createEvaluation("cand-mid", "Song", 0.80, null);

            List<CandidateMatchEvaluation> result = rankingEngine.rankCandidates(List.of(low, high, mid));

            assertThat(result).extracting(CandidateMatchEvaluation::candidateId)
                    .containsExactly("cand-high", "cand-mid", "cand-low");
        }
    }

    @Nested
    @DisplayName("Multi-level Deterministic Tie-breaking Ladder")
    class TieBreakingLadder {

        @Test
        @DisplayName("should break overall score tie using title score descending")
        void shouldBreakTieUsingTitleScore() {
            MatchScoreBreakdown b1 = MatchScoreBreakdown.builder().titleScore(0.95).artistScore(0.80).build();
            MatchScoreBreakdown b2 = MatchScoreBreakdown.builder().titleScore(0.85).artistScore(0.90).build();

            CandidateMatchEvaluation e1 = createEvaluation("cand-1", "Song A", 0.85, b1);
            CandidateMatchEvaluation e2 = createEvaluation("cand-2", "Song B", 0.85, b2);

            List<CandidateMatchEvaluation> result = rankingEngine.rankCandidates(List.of(e2, e1));

            assertThat(result).extracting(CandidateMatchEvaluation::candidateId)
                    .containsExactly("cand-1", "cand-2");
        }

        @Test
        @DisplayName("should break title score tie using artist score descending")
        void shouldBreakTieUsingArtistScore() {
            MatchScoreBreakdown b1 = MatchScoreBreakdown.builder().titleScore(0.90).artistScore(0.95).build();
            MatchScoreBreakdown b2 = MatchScoreBreakdown.builder().titleScore(0.90).artistScore(0.85).build();

            CandidateMatchEvaluation e1 = createEvaluation("cand-1", "Song A", 0.85, b1);
            CandidateMatchEvaluation e2 = createEvaluation("cand-2", "Song B", 0.85, b2);

            List<CandidateMatchEvaluation> result = rankingEngine.rankCandidates(List.of(e2, e1));

            assertThat(result).extracting(CandidateMatchEvaluation::candidateId)
                    .containsExactly("cand-1", "cand-2");
        }

        @Test
        @DisplayName("should break artist score tie using official signal score descending")
        void shouldBreakTieUsingOfficialSignalScore() {
            MatchScoreBreakdown b1 = MatchScoreBreakdown.builder().titleScore(0.90).artistScore(0.90).officialSignalScore(1.0).build();
            MatchScoreBreakdown b2 = MatchScoreBreakdown.builder().titleScore(0.90).artistScore(0.90).officialSignalScore(0.0).build();

            CandidateMatchEvaluation e1 = createEvaluation("cand-1", "Song A", 0.85, b1);
            CandidateMatchEvaluation e2 = createEvaluation("cand-2", "Song B", 0.85, b2);

            List<CandidateMatchEvaluation> result = rankingEngine.rankCandidates(List.of(e2, e1));

            assertThat(result).extracting(CandidateMatchEvaluation::candidateId)
                    .containsExactly("cand-1", "cand-2");
        }

        @Test
        @DisplayName("should break official signal tie using version penalty ascending (lower penalty preferred)")
        void shouldBreakTieUsingVersionPenaltyAscending() {
            MatchScoreBreakdown b1 = MatchScoreBreakdown.builder()
                    .titleScore(0.90).artistScore(0.90).officialSignalScore(1.0).versionPenalty(0.0).build();
            MatchScoreBreakdown b2 = MatchScoreBreakdown.builder()
                    .titleScore(0.90).artistScore(0.90).officialSignalScore(1.0).versionPenalty(0.15).build();

            CandidateMatchEvaluation e1 = createEvaluation("cand-1", "Song A", 0.85, b1);
            CandidateMatchEvaluation e2 = createEvaluation("cand-2", "Song B", 0.85, b2);

            List<CandidateMatchEvaluation> result = rankingEngine.rankCandidates(List.of(e2, e1));

            assertThat(result).extracting(CandidateMatchEvaluation::candidateId)
                    .containsExactly("cand-1", "cand-2");
        }

        @Test
        @DisplayName("should break version penalty tie using duration score descending")
        void shouldBreakTieUsingDurationScore() {
            MatchScoreBreakdown b1 = MatchScoreBreakdown.builder()
                    .titleScore(0.90).artistScore(0.90).officialSignalScore(1.0).versionPenalty(0.0).durationScore(1.0).build();
            MatchScoreBreakdown b2 = MatchScoreBreakdown.builder()
                    .titleScore(0.90).artistScore(0.90).officialSignalScore(1.0).versionPenalty(0.0).durationScore(0.80).build();

            CandidateMatchEvaluation e1 = createEvaluation("cand-1", "Song A", 0.85, b1);
            CandidateMatchEvaluation e2 = createEvaluation("cand-2", "Song B", 0.85, b2);

            List<CandidateMatchEvaluation> result = rankingEngine.rankCandidates(List.of(e2, e1));

            assertThat(result).extracting(CandidateMatchEvaluation::candidateId)
                    .containsExactly("cand-1", "cand-2");
        }

        @Test
        @DisplayName("should break complete score tie using candidate ID lexicographically")
        void shouldBreakTieUsingCandidateId() {
            MatchScoreBreakdown b = MatchScoreBreakdown.builder().titleScore(1.0).artistScore(1.0).build();

            CandidateMatchEvaluation e1 = createEvaluation("cand-alpha", "Song A", 0.90, b);
            CandidateMatchEvaluation e2 = createEvaluation("cand-beta", "Song B", 0.90, b);

            List<CandidateMatchEvaluation> result = rankingEngine.rankCandidates(List.of(e2, e1));

            assertThat(result).extracting(CandidateMatchEvaluation::candidateId)
                    .containsExactly("cand-alpha", "cand-beta");
        }
    }

    @Nested
    @DisplayName("Immutability, Null Handling, and Determinism")
    class RobustnessAndImmutability {

        @Test
        @DisplayName("should not mutate caller-owned input collection")
        void shouldNotMutateInputCollection() {
            CandidateMatchEvaluation e1 = createEvaluation("cand-1", "Song 1", 0.70, null);
            CandidateMatchEvaluation e2 = createEvaluation("cand-2", "Song 2", 0.90, null);

            List<CandidateMatchEvaluation> original = new ArrayList<>(List.of(e1, e2));
            List<CandidateMatchEvaluation> ranked = rankingEngine.rankCandidates(original);

            // Original list remains in insertion order
            assertThat(original.get(0).candidateId()).isEqualTo("cand-1");
            assertThat(original.get(1).candidateId()).isEqualTo("cand-2");

            // Ranked list is ordered descending
            assertThat(ranked.get(0).candidateId()).isEqualTo("cand-2");
            assertThat(ranked.get(1).candidateId()).isEqualTo("cand-1");
        }

        @Test
        @DisplayName("should return unmodifiable list")
        void shouldReturnUnmodifiableList() {
            CandidateMatchEvaluation e = createEvaluation("cand-1", "Song", 0.80, null);
            List<CandidateMatchEvaluation> ranked = rankingEngine.rankCandidates(List.of(e));

            assertThatThrownBy(() -> ranked.add(e))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("should treat null overall score as 0.0 and place after scored candidates")
        void shouldHandleNullOverallScore() {
            CandidateMatchEvaluation unscored = createEvaluation("cand-unscored", "Song", null, null);
            CandidateMatchEvaluation scored = createEvaluation("cand-scored", "Song", 0.50, null);

            List<CandidateMatchEvaluation> ranked = rankingEngine.rankCandidates(List.of(unscored, scored));

            assertThat(ranked).extracting(CandidateMatchEvaluation::candidateId)
                    .containsExactly("cand-scored", "cand-unscored");
        }

        @Test
        @DisplayName("should throw NullPointerException on null evaluations list")
        void shouldThrowOnNullInput() {
            assertThatThrownBy(() -> rankingEngine.rankCandidates(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should produce deterministic ranking on repeated executions")
        void shouldProduceDeterministicRanking() {
            CandidateMatchEvaluation e1 = createEvaluation("cand-1", "Song", 0.85, null);
            CandidateMatchEvaluation e2 = createEvaluation("cand-2", "Song", 0.85, null);

            List<CandidateMatchEvaluation> run1 = rankingEngine.rankCandidates(List.of(e1, e2));
            List<CandidateMatchEvaluation> run2 = rankingEngine.rankCandidates(List.of(e2, e1));

            assertThat(run1).extracting(CandidateMatchEvaluation::candidateId)
                    .containsExactlyElementsOf(run2.stream().map(CandidateMatchEvaluation::candidateId).toList());
        }
    }

    private CandidateMatchEvaluation createEvaluation(
            String id,
            String title,
            Double overallScore,
            MatchScoreBreakdown breakdown
    ) {
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                id, title, "Artist", "Description", "https://thumb.jpg", "2021-01-01", Duration.ofMinutes(4)
        );
        CandidateTrackFeatures features = new CandidateTrackFeatures(
                id, title, title.toLowerCase(), title, "Artist", "artist",
                Duration.ofMinutes(4), 240000L, true, true, "Description", "2021-01-01",
                CandidateVersionEvidence.empty(), OfficialSignalEvidence.none(), candidate
        );
        return CandidateMatchEvaluation.of(
                candidate,
                overallScore,
                MatchConfidence.GOOD,
                breakdown != null ? breakdown : MatchScoreBreakdown.empty(),
                List.of(),
                features
        );
    }
}
