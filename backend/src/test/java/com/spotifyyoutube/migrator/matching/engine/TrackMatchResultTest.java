package com.spotifyyoutube.migrator.matching.engine;

import com.spotifyyoutube.migrator.matching.normalization.NormalizedArtistCredit;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.RawTrackMetadata;
import com.spotifyyoutube.migrator.matching.normalization.TokenizedTitle;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TrackMatchResult Contract Tests")
class TrackMatchResultTest {

    private NormalizedTrackMetadata sampleSourceTrack() {
        return new NormalizedTrackMetadata(
                RawTrackMetadata.of("Levitating", "Dua Lipa", "Future Nostalgia", 203000L),
                "levitating",
                new NormalizedArtistCredit("dua lipa", List.of()),
                List.of("dua lipa"),
                List.of(),
                "future nostalgia",
                Duration.ofSeconds(203),
                new TokenizedTitle("Levitating", "levitating", false, Set.of(), null, null, Set.of(), List.of(), List.of(), List.of())
        );
    }

    private DestinationTrackCandidate sampleCandidate(String id) {
        return new DestinationTrackCandidate(
                id,
                "Dua Lipa - Levitating (Official Music Video)",
                "Dua Lipa",
                "Official Video",
                "https://thumb.jpg",
                "2020-10-02",
                Duration.ofSeconds(203)
        );
    }

    @Nested
    @DisplayName("Validation and Required Fields")
    class ValidationAndRequiredFields {

        @Test
        @DisplayName("should reject null sourceTrack with NullPointerException")
        void shouldRejectNullSourceTrack() {
            assertThatThrownBy(() -> new TrackMatchResult(
                    "sp-1", null, MatchDecision.MATCH, null, null, null, null, null, List.of(), List.of()
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("NormalizedTrackMetadata must not be null");
        }

        @Test
        @DisplayName("should reject null decision with NullPointerException")
        void shouldRejectNullDecision() {
            assertThatThrownBy(() -> new TrackMatchResult(
                    "sp-1", sampleSourceTrack(), null, null, null, null, null, null, List.of(), List.of()
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("MatchDecision must not be null");
        }

        @Test
        @DisplayName("should enforce selected candidate consistency with candidate evaluations")
        void shouldEnforceSelectedCandidateConsistency() {
            DestinationTrackCandidate selected = sampleCandidate("selected-cand");
            DestinationTrackCandidate evaluatedOther = sampleCandidate("other-cand");
            CandidateMatchEvaluation evalOther = CandidateMatchEvaluation.unevaluated(evaluatedOther, null);

            assertThatThrownBy(() -> new TrackMatchResult(
                    "sp-1",
                    sampleSourceTrack(),
                    MatchDecision.MATCH,
                    selected,
                    0.95,
                    null,
                    MatchConfidence.EXCELLENT,
                    null,
                    List.of(evalOther), // selected-cand is NOT in evaluations!
                    List.of()
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Selected candidate must be present among candidate evaluations");
        }

        @Test
        @DisplayName("should accept selected candidate when present among candidate evaluations")
        void shouldAcceptSelectedCandidateWhenPresentInEvaluations() {
            DestinationTrackCandidate selected = sampleCandidate("matching-cand");
            CandidateMatchEvaluation eval = CandidateMatchEvaluation.unevaluated(selected, null);

            TrackMatchResult result = new TrackMatchResult(
                    "sp-1",
                    sampleSourceTrack(),
                    MatchDecision.MATCH,
                    selected,
                    0.95,
                    0.98,
                    MatchConfidence.EXCELLENT,
                    null,
                    List.of(eval),
                    List.of("High confidence match")
            );

            assertThat(result.selectedCandidate()).isEqualTo(selected);
            assertThat(result.isMatch()).isTrue();
            assertThat(result.isReviewRequired()).isFalse();
            assertThat(result.isNoMatch()).isFalse();
            assertThat(result.hasSelectedCandidate()).isTrue();
            assertThat(result.hasScore()).isTrue();
            assertThat(result.hasCalibratedProbability()).isTrue();
            assertThat(result.hasConfidence()).isTrue();
            assertThat(result.score()).isEqualTo(0.95);
            assertThat(result.calibratedProbability()).isEqualTo(0.98);
            assertThat(result.confidence()).isEqualTo(MatchConfidence.EXCELLENT);
        }
    }

    @Nested
    @DisplayName("Factory Methods")
    class FactoryMethods {

        @Test
        @DisplayName("noMatch factory should construct valid NO_MATCH result with reasons")
        void noMatchFactoryShouldConstructResult() {
            TrackMatchResult result = TrackMatchResult.noMatch(
                    "sp-123",
                    sampleSourceTrack(),
                    List.of("Zero candidates found")
            );

            assertThat(result.decision()).isEqualTo(MatchDecision.NO_MATCH);
            assertThat(result.isNoMatch()).isTrue();
            assertThat(result.selectedCandidate()).isNull();
            assertThat(result.score()).isNull();
            assertThat(result.candidateEvaluations()).isEmpty();
            assertThat(result.matchReasons()).containsExactly("Zero candidates found");
            assertThat(result.sourceTrackId()).isEqualTo("sp-123");
        }

        @Test
        @DisplayName("unevaluated factory should construct NO_MATCH result from TrackMatchRequest")
        void unevaluatedFactoryShouldConstructFromRequest() {
            TrackMatchRequest request = new TrackMatchRequest(
                    "sp-123",
                    sampleSourceTrack(),
                    List.of()
            );

            TrackMatchResult result = TrackMatchResult.unevaluated(request, "Candidate pool empty");

            assertThat(result.decision()).isEqualTo(MatchDecision.NO_MATCH);
            assertThat(result.sourceTrackId()).isEqualTo("sp-123");
            assertThat(result.sourceTrack()).isEqualTo(request.sourceTrack());
            assertThat(result.matchReasons()).containsExactly("Candidate pool empty");
        }

        @Test
        @DisplayName("of factory should construct valid result with null and non-null calibrated probability")
        void ofFactoryShouldConstructResult() {
            DestinationTrackCandidate cand = sampleCandidate("c-1");
            CandidateMatchEvaluation eval = CandidateMatchEvaluation.unevaluated(cand, null);

            TrackMatchResult resUncalibrated = TrackMatchResult.of(
                    "sp-1", sampleSourceTrack(), MatchDecision.MATCH, cand,
                    0.92, MatchConfidence.EXCELLENT, null, List.of(eval), List.of("reason")
            );
            assertThat(resUncalibrated.calibratedProbability()).isNull();
            assertThat(resUncalibrated.hasCalibratedProbability()).isFalse();

            TrackMatchResult resCalibrated = TrackMatchResult.of(
                    "sp-1", sampleSourceTrack(), MatchDecision.MATCH, cand,
                    0.92, 0.88, MatchConfidence.EXCELLENT, null, List.of(eval), List.of("reason")
            );
            assertThat(resCalibrated.calibratedProbability()).isEqualTo(0.88);
            assertThat(resCalibrated.hasCalibratedProbability()).isTrue();
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("candidateEvaluations and matchReasons should be unmodifiable")
        void collectionsShouldBeUnmodifiable() {
            List<String> mutableReasons = new ArrayList<>();
            mutableReasons.add("Initial reason");

            TrackMatchResult result = new TrackMatchResult(
                    "sp-1",
                    sampleSourceTrack(),
                    MatchDecision.NO_MATCH,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    mutableReasons
            );

            // Mutating local list has no effect
            mutableReasons.add("Second reason");
            assertThat(result.matchReasons()).hasSize(1);

            // Mutating through accessor throws exception
            assertThatThrownBy(() -> result.matchReasons().add("Third reason"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> result.candidateEvaluations().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
