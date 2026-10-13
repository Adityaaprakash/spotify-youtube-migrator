package com.spotifyyoutube.migrator.matching.engine;

import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.CandidateVersionEvidence;
import com.spotifyyoutube.migrator.matching.engine.feature.OfficialSignalEvidence;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CandidateMatchEvaluation Contract Tests")
class CandidateMatchEvaluationTest {

    private DestinationTrackCandidate sampleCandidate() {
        return new DestinationTrackCandidate(
                "video-123",
                "Get Lucky",
                "Daft Punk",
                "Official Video",
                "https://thumb.jpg",
                "2013-04-19",
                Duration.ofSeconds(248)
        );
    }

    private CandidateTrackFeatures sampleFeatures(DestinationTrackCandidate candidate) {
        return new CandidateTrackFeatures(
                candidate.id(),
                candidate.title(),
                "get lucky",
                "get lucky",
                candidate.channelOrArtist(),
                "daft punk",
                candidate.duration(),
                248000L,
                true,
                true,
                candidate.description(),
                candidate.publishedAt(),
                CandidateVersionEvidence.empty(),
                OfficialSignalEvidence.none(),
                candidate
        );
    }

    @Nested
    @DisplayName("Validation and Construction")
    class ValidationAndConstruction {

        @Test
        @DisplayName("should reject null candidate with NullPointerException")
        void shouldRejectNullCandidate() {
            assertThatThrownBy(() -> new CandidateMatchEvaluation(
                    null, 0.9, 0.95, MatchConfidence.EXCELLENT, null, List.of(), null
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("DestinationTrackCandidate must not be null");
        }

        @Test
        @DisplayName("unevaluated factory should produce unevaluated evaluation with honest nulls")
        void unevaluatedFactoryShouldProduceUnevaluatedInstance() {
            DestinationTrackCandidate candidate = sampleCandidate();
            CandidateTrackFeatures features = sampleFeatures(candidate);

            CandidateMatchEvaluation eval = CandidateMatchEvaluation.unevaluated(candidate, features);

            assertThat(eval.candidate()).isEqualTo(candidate);
            assertThat(eval.candidateId()).isEqualTo("video-123");
            assertThat(eval.candidateTitle()).isEqualTo("Get Lucky");
            assertThat(eval.overallScore()).isNull();
            assertThat(eval.calibratedProbability()).isNull();
            assertThat(eval.confidence()).isNull();
            assertThat(eval.scoreBreakdown()).isNull();
            assertThat(eval.matchReasons()).isEmpty();
            assertThat(eval.candidateFeatures()).isEqualTo(features);

            assertThat(eval.hasOverallScore()).isFalse();
            assertThat(eval.hasCalibratedProbability()).isFalse();
            assertThat(eval.hasConfidence()).isFalse();
            assertThat(eval.hasScoreBreakdown()).isFalse();
            assertThat(eval.hasCandidateFeatures()).isTrue();
        }

        @Test
        @DisplayName("of factory should populate scores, confidence, breakdown, and reasons")
        void ofFactoryShouldPopulateEvaluatedFields() {
            DestinationTrackCandidate candidate = sampleCandidate();
            CandidateTrackFeatures features = sampleFeatures(candidate);
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.builder()
                    .titleScore(0.95)
                    .artistScore(0.92)
                    .build();

            CandidateMatchEvaluation eval = CandidateMatchEvaluation.of(
                    candidate,
                    0.94,
                    MatchConfidence.EXCELLENT,
                    breakdown,
                    List.of("Exact artist match", "High title similarity"),
                    features
            );

            assertThat(eval.candidate()).isEqualTo(candidate);
            assertThat(eval.overallScore()).isEqualTo(0.94);
            assertThat(eval.confidence()).isEqualTo(MatchConfidence.EXCELLENT);
            assertThat(eval.scoreBreakdown()).isEqualTo(breakdown);
            assertThat(eval.matchReasons()).containsExactly("Exact artist match", "High title similarity");

            assertThat(eval.getOverallScore()).contains(0.94);
            assertThat(eval.getConfidence()).contains(MatchConfidence.EXCELLENT);
            assertThat(eval.getScoreBreakdown()).contains(breakdown);
        }

        @Test
        @DisplayName("of factory with calibrated probability should populate calibratedProbability")
        void ofFactoryWithCalibratedProbabilityShouldPopulateField() {
            DestinationTrackCandidate candidate = sampleCandidate();
            CandidateTrackFeatures features = sampleFeatures(candidate);
            MatchScoreBreakdown breakdown = MatchScoreBreakdown.builder()
                    .titleScore(0.95)
                    .artistScore(0.92)
                    .build();

            CandidateMatchEvaluation eval = CandidateMatchEvaluation.of(
                    candidate,
                    0.94,
                    0.88,
                    MatchConfidence.EXCELLENT,
                    breakdown,
                    List.of("High match likelihood"),
                    features
            );

            assertThat(eval.candidate()).isEqualTo(candidate);
            assertThat(eval.overallScore()).isEqualTo(0.94);
            assertThat(eval.calibratedProbability()).isEqualTo(0.88);
            assertThat(eval.hasCalibratedProbability()).isTrue();
            assertThat(eval.getCalibratedProbability()).contains(0.88);
            assertThat(eval.confidence()).isEqualTo(MatchConfidence.EXCELLENT);
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("matchReasons collection should be unmodifiable")
        void matchReasonsShouldBeUnmodifiable() {
            List<String> mutableReasons = new ArrayList<>();
            mutableReasons.add("Initial reason");

            CandidateMatchEvaluation eval = new CandidateMatchEvaluation(
                    sampleCandidate(),
                    0.8,
                    null,
                    MatchConfidence.GOOD,
                    null,
                    mutableReasons,
                    null
            );

            // Mutating outer list has no effect
            mutableReasons.add("Second reason");
            assertThat(eval.matchReasons()).hasSize(1);

            // Mutating through accessor throws exception
            assertThatThrownBy(() -> eval.matchReasons().add("Third reason"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
