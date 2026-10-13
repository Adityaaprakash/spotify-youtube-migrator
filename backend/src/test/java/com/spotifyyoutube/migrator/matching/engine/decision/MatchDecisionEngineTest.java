package com.spotifyyoutube.migrator.matching.engine.decision;

import com.spotifyyoutube.migrator.matching.engine.CandidateMatchEvaluation;
import com.spotifyyoutube.migrator.matching.engine.calibration.DefaultMatchProbabilityCalibrator;
import com.spotifyyoutube.migrator.matching.engine.MatchConfidence;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.matching.engine.MatchScoreBreakdown;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchResult;
import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.CandidateVersionEvidence;
import com.spotifyyoutube.migrator.matching.engine.feature.OfficialSignalEvidence;
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

@DisplayName("MatchDecisionEngine Unit Tests")
class MatchDecisionEngineTest {

    private MatchDecisionEngine decisionEngine;
    private NormalizedTrackMetadata sourceTrack;

    @BeforeEach
    void setUp() {
        decisionEngine = new DefaultMatchDecisionEngine();
        sourceTrack = createSourceTrack("Get Lucky", "Daft Punk");
    }

    @Nested
    @DisplayName("Empty Candidates and Unresolved Matching")
    class EmptyCandidatesAndUnresolved {

        @Test
        @DisplayName("should decide NO_MATCH when candidate list is empty")
        void shouldDecideNoMatchOnEmptyCandidates() {
            TrackMatchResult result = decisionEngine.decide("track-1", sourceTrack, List.of());

            assertThat(result.decision()).isEqualTo(MatchDecision.NO_MATCH);
            assertThat(result.selectedCandidate()).isNull();
            assertThat(result.hasSelectedCandidate()).isFalse();
            assertThat(result.matchReasons()).anyMatch(r -> r.contains("No destination candidates discovered"));
        }

        @Test
        @DisplayName("should decide NO_MATCH when top candidate score is below minimum viable threshold (< 0.50)")
        void shouldDecideNoMatchWhenScoreBelowMinimumViable() {
            CandidateMatchEvaluation eval = createEvaluation("cand-1", "Completely Unrelated Track", 0.4999, 0.0);

            TrackMatchResult result = decisionEngine.decide("track-1", sourceTrack, List.of(eval));

            assertThat(result.decision()).isEqualTo(MatchDecision.NO_MATCH);
            assertThat(result.selectedCandidate()).isNull();
            assertThat(result.score()).isEqualTo(0.4999);
            assertThat(result.confidence()).isEqualTo(MatchConfidence.POOR);
            assertThat(result.matchReasons()).anyMatch(r -> r.contains("below minimum viable threshold"));
        }
    }

    @Nested
    @DisplayName("Borderline Score and Review Required")
    class BorderlineScores {

        @Test
        @DisplayName("should decide REVIEW_REQUIRED when score is exactly at minimum viable threshold (0.50)")
        void shouldDecideReviewRequiredAtMinimumViableThreshold() {
            CandidateMatchEvaluation eval = createEvaluation("cand-1", "Borderline Match", 0.50, 0.0);

            TrackMatchResult result = decisionEngine.decide("track-1", sourceTrack, List.of(eval));

            assertThat(result.decision()).isEqualTo(MatchDecision.REVIEW_REQUIRED);
            assertThat(result.selectedCandidate()).isNotNull();
            assertThat(result.selectedCandidate().id()).isEqualTo("cand-1");
            assertThat(result.score()).isEqualTo(0.50);
            assertThat(result.confidence()).isEqualTo(MatchConfidence.POOR);
            assertThat(result.matchReasons()).anyMatch(r -> r.contains("below automated match threshold"));
        }

        @Test
        @DisplayName("should decide REVIEW_REQUIRED when score is immediately below match threshold (0.7499)")
        void shouldDecideReviewRequiredImmediatelyBelowMatchThreshold() {
            CandidateMatchEvaluation eval = createEvaluation("cand-1", "Good But Borderline Match", 0.7499, 0.0);

            TrackMatchResult result = decisionEngine.decide("track-1", sourceTrack, List.of(eval));

            assertThat(result.decision()).isEqualTo(MatchDecision.REVIEW_REQUIRED);
            assertThat(result.selectedCandidate()).isNotNull();
            assertThat(result.score()).isEqualTo(0.7499);
            assertThat(result.matchReasons()).anyMatch(r -> r.contains("Borderline similarity requires manual user review"));
        }
    }

    @Nested
    @DisplayName("Version Penalty Discrepancy Gate")
    class VersionPenaltyDiscrepancy {

        @Test
        @DisplayName("should decide REVIEW_REQUIRED when version penalty exceeds maximum allowed (> 0.10) despite high score")
        void shouldDecideReviewRequiredWhenVersionPenaltyExceedsMax() {
            // High score 0.85, but live mismatch penalty 0.15
            CandidateMatchEvaluation eval = createEvaluation("cand-live", "Get Lucky (Live)", 0.85, 0.15);

            TrackMatchResult result = decisionEngine.decide("track-1", sourceTrack, List.of(eval));

            assertThat(result.decision()).isEqualTo(MatchDecision.REVIEW_REQUIRED);
            assertThat(result.selectedCandidate()).isNotNull();
            assertThat(result.selectedCandidate().id()).isEqualTo("cand-live");
            assertThat(result.matchReasons()).anyMatch(r -> r.contains("recording version discrepancy penalty"));
        }

        @Test
        @DisplayName("should allow MATCH when version penalty is within threshold (<= 0.10)")
        void shouldAllowMatchWhenPenaltyWithinThreshold() {
            // Score 0.85, penalty 0.10
            CandidateMatchEvaluation eval = createEvaluation("cand-1", "Get Lucky", 0.85, 0.10);

            TrackMatchResult result = decisionEngine.decide("track-1", sourceTrack, List.of(eval));

            assertThat(result.decision()).isEqualTo(MatchDecision.MATCH);
            assertThat(result.confidence()).isEqualTo(MatchConfidence.GOOD);
        }

        @Test
        @DisplayName("should decide REVIEW_REQUIRED when version penalty reduces score below minimum viable threshold (< 0.50) but base score is viable")
        void shouldDecideReviewRequiredWhenPenaltyReducesScoreBelowMinimumViable() {
            // Base score 0.55 >= 0.50, penalty 0.35 -> topScore = 0.20 < 0.50
            CandidateMatchEvaluation eval = createEvaluation("cand-cover", "Get Lucky (Cover)", 0.20, 0.35);

            TrackMatchResult result = decisionEngine.decide("track-1", sourceTrack, List.of(eval));

            assertThat(result.decision()).isEqualTo(MatchDecision.REVIEW_REQUIRED);
            assertThat(result.selectedCandidate()).isNotNull();
            assertThat(result.selectedCandidate().id()).isEqualTo("cand-cover");
            assertThat(result.matchReasons()).anyMatch(r -> r.contains("recording version discrepancy penalty"));
        }
    }

    @Nested
    @DisplayName("Ambiguity Margin Between Leading Candidates")
    class AmbiguityMargin {

        @Test
        @DisplayName("should decide REVIEW_REQUIRED when top two candidates differ by less than ambiguity margin (< 0.05)")
        void shouldDecideReviewRequiredWhenCandidatesWithinAmbiguityMargin() {
            CandidateMatchEvaluation top = createEvaluation("cand-1", "Get Lucky (Official Audio)", 0.88, 0.0);
            CandidateMatchEvaluation runnerUp = createEvaluation("cand-2", "Get Lucky (Music Video)", 0.86, 0.0);

            // Margin = 0.88 - 0.86 = 0.02 < 0.05
            TrackMatchResult result = decisionEngine.decide("track-1", sourceTrack, List.of(top, runnerUp));

            assertThat(result.decision()).isEqualTo(MatchDecision.REVIEW_REQUIRED);
            assertThat(result.selectedCandidate()).isNotNull();
            assertThat(result.selectedCandidate().id()).isEqualTo("cand-1");
            assertThat(result.confidence()).isEqualTo(MatchConfidence.GOOD);
            assertThat(result.matchReasons()).anyMatch(r -> r.contains("Ambiguous match"));
            assertThat(result.matchReasons()).anyMatch(r -> r.contains("within ambiguity margin"));
        }

        @Test
        @DisplayName("should decide MATCH when score difference is exactly at ambiguity margin (0.05)")
        void shouldDecideMatchWhenDifferenceAtAmbiguityMargin() {
            CandidateMatchEvaluation top = createEvaluation("cand-1", "Get Lucky (Official Audio)", 0.90, 0.0);
            CandidateMatchEvaluation runnerUp = createEvaluation("cand-2", "Get Lucky (Alternate)", 0.85, 0.0);

            // Margin = 0.90 - 0.85 = 0.05 -> not strictly < 0.05
            TrackMatchResult result = decisionEngine.decide("track-1", sourceTrack, List.of(top, runnerUp));

            assertThat(result.decision()).isEqualTo(MatchDecision.MATCH);
            assertThat(result.selectedCandidate().id()).isEqualTo("cand-1");
        }

        @Test
        @DisplayName("should decide MATCH when score difference exceeds ambiguity margin (> 0.05)")
        void shouldDecideMatchWhenDifferenceExceedsAmbiguityMargin() {
            CandidateMatchEvaluation top = createEvaluation("cand-1", "Get Lucky (Official Audio)", 0.92, 0.0);
            CandidateMatchEvaluation runnerUp = createEvaluation("cand-2", "Get Lucky (Radio Edit)", 0.80, 0.0);

            // Margin = 0.92 - 0.80 = 0.12 > 0.05
            TrackMatchResult result = decisionEngine.decide("track-1", sourceTrack, List.of(top, runnerUp));

            assertThat(result.decision()).isEqualTo(MatchDecision.MATCH);
            assertThat(result.selectedCandidate().id()).isEqualTo("cand-1");
            assertThat(result.confidence()).isEqualTo(MatchConfidence.EXCELLENT);
        }
    }

    @Nested
    @DisplayName("Unambiguous High-Confidence Matches")
    class UnambiguousMatches {

        @Test
        @DisplayName("should decide MATCH with GOOD confidence for score between 0.75 and 0.90")
        void shouldDecideMatchWithGoodConfidence() {
            CandidateMatchEvaluation top = createEvaluation("cand-1", "Get Lucky", 0.75, 0.0);

            TrackMatchResult result = decisionEngine.decide("track-1", sourceTrack, List.of(top));

            assertThat(result.decision()).isEqualTo(MatchDecision.MATCH);
            assertThat(result.confidence()).isEqualTo(MatchConfidence.GOOD);
            assertThat(result.selectedCandidate().id()).isEqualTo("cand-1");
        }

        @Test
        @DisplayName("should decide MATCH with EXCELLENT confidence for score >= 0.90")
        void shouldDecideMatchWithExcellentConfidence() {
            CandidateMatchEvaluation top = createEvaluation("cand-1", "Get Lucky", 0.95, 0.0);

            TrackMatchResult result = decisionEngine.decide("track-1", sourceTrack, List.of(top));

            assertThat(result.decision()).isEqualTo(MatchDecision.MATCH);
            assertThat(result.confidence()).isEqualTo(MatchConfidence.EXCELLENT);
            assertThat(result.selectedCandidate().id()).isEqualTo("cand-1");
        }
    }

    @Nested
    @DisplayName("Calibrated Probability Decision Policy")
    class CalibratedProbabilityPolicy {

        private MatchDecisionEngine validatedDecisionEngine;
        private MatchDecisionEngine unvalidatedConfiguredEngine;

        @BeforeEach
        void setUpCalibratedEngines() {
            validatedDecisionEngine = new DefaultMatchDecisionEngine(
                    DefaultMatchProbabilityCalibrator.withValidatedPlattScaling(-10.0, 7.5, "v1.0-validated")
            );
            unvalidatedConfiguredEngine = new DefaultMatchDecisionEngine(
                    DefaultMatchProbabilityCalibrator.withPlattScaling(-10.0, 7.5, "v1.0-unvalidated")
            );
        }

        @Test
        @DisplayName("should decide REVIEW_REQUIRED when validated probability is below threshold (< 0.70)")
        void shouldDecideReviewRequiredWhenProbabilityBelowThreshold() {
            // Score 0.82 >= 0.75 match threshold, but calibrated probability 0.65 < 0.70
            CandidateMatchEvaluation eval = createEvaluationWithProbability(
                    "cand-1", "Get Lucky", 0.82, 0.0, 0.65
            );

            TrackMatchResult result = validatedDecisionEngine.decide("track-1", sourceTrack, List.of(eval));

            assertThat(result.decision()).isEqualTo(MatchDecision.REVIEW_REQUIRED);
            assertThat(result.selectedCandidate()).isNotNull();
            assertThat(result.selectedCandidate().id()).isEqualTo("cand-1");
            assertThat(result.calibratedProbability()).isEqualTo(0.65);
            assertThat(result.matchReasons()).anyMatch(r -> r.contains("calibrated match probability"));
            assertThat(result.matchReasons()).anyMatch(r -> r.contains("below minimum match probability threshold"));
        }

        @Test
        @DisplayName("should decide MATCH when validated probability meets threshold (>= 0.70)")
        void shouldDecideMatchWhenProbabilityMeetsThreshold() {
            // Score 0.85 >= 0.75, calibrated probability 0.80 >= 0.70
            CandidateMatchEvaluation eval = createEvaluationWithProbability(
                    "cand-1", "Get Lucky", 0.85, 0.0, 0.80
            );

            TrackMatchResult result = validatedDecisionEngine.decide("track-1", sourceTrack, List.of(eval));

            assertThat(result.decision()).isEqualTo(MatchDecision.MATCH);
            assertThat(result.selectedCandidate().id()).isEqualTo("cand-1");
            assertThat(result.calibratedProbability()).isEqualTo(0.80);
            assertThat(result.matchReasons()).anyMatch(r -> r.contains("Calibrated match probability confirmed"));
        }

        @Test
        @DisplayName("should not let high calibrated probability override version penalty gate")
        void shouldNotLetHighProbabilityOverrideVersionPenalty() {
            // Score 0.88, probability 0.95, but version penalty 0.15 > 0.10
            CandidateMatchEvaluation eval = createEvaluationWithProbability(
                    "cand-live", "Get Lucky (Live)", 0.88, 0.15, 0.95
            );

            TrackMatchResult result = validatedDecisionEngine.decide("track-1", sourceTrack, List.of(eval));

            assertThat(result.decision()).isEqualTo(MatchDecision.REVIEW_REQUIRED);
            assertThat(result.selectedCandidate().id()).isEqualTo("cand-live");
            assertThat(result.matchReasons()).anyMatch(r -> r.contains("recording version discrepancy penalty"));
        }

        @Test
        @DisplayName("should not let high calibrated probability override ambiguity margin gate")
        void shouldNotLetHighProbabilityOverrideAmbiguityMargin() {
            CandidateMatchEvaluation top = createEvaluationWithProbability(
                    "cand-1", "Get Lucky (Audio)", 0.88, 0.0, 0.92
            );
            CandidateMatchEvaluation runnerUp = createEvaluationWithProbability(
                    "cand-2", "Get Lucky (Video)", 0.86, 0.0, 0.89
            );

            // Margin = 0.88 - 0.86 = 0.02 < 0.05
            TrackMatchResult result = validatedDecisionEngine.decide("track-1", sourceTrack, List.of(top, runnerUp));

            assertThat(result.decision()).isEqualTo(MatchDecision.REVIEW_REQUIRED);
            assertThat(result.selectedCandidate().id()).isEqualTo("cand-1");
            assertThat(result.matchReasons()).anyMatch(r -> r.contains("Ambiguous match"));
        }

        @Test
        @DisplayName("should indicate uncalibrated heuristic fallback when probability is null")
        void shouldIndicateUncalibratedFallbackWhenProbabilityIsNull() {
            CandidateMatchEvaluation eval = createEvaluationWithProbability(
                    "cand-1", "Get Lucky", 0.85, 0.0, null
            );

            TrackMatchResult result = decisionEngine.decide("track-1", sourceTrack, List.of(eval));

            assertThat(result.decision()).isEqualTo(MatchDecision.MATCH);
            assertThat(result.calibratedProbability()).isNull();
            assertThat(result.matchReasons()).anyMatch(r -> r.contains("Probability calibration model unavailable"));
        }

        @Test
        @DisplayName("should not trigger calibrated probability gate when model is unvalidated")
        void shouldNotTriggerProbabilityGateWhenModelIsUnvalidated() {
            // Score 0.82 >= 0.75, unvalidated probability 0.65 < 0.70
            CandidateMatchEvaluation eval = createEvaluationWithProbability(
                    "cand-1", "Get Lucky", 0.82, 0.0, 0.65
            );

            // When evaluated with unvalidated configured engine, probability does not trigger Gate 6
            TrackMatchResult result = unvalidatedConfiguredEngine.decide("track-1", sourceTrack, List.of(eval));

            assertThat(result.decision()).isEqualTo(MatchDecision.MATCH);
            assertThat(result.calibratedProbability()).isNull();
            assertThat(result.matchReasons()).anyMatch(r ->
                    r.contains("Calibration transformation is unvalidated; evaluated using heuristic"));
        }

        @Test
        @DisplayName("should assign EXCELLENT confidence only when both score and probability are high")
        void shouldAssignConfidenceBasedOnBothScoreAndProbability() {
            // High score (0.92 >= 0.90) and high probability (0.88 >= 0.85) -> EXCELLENT
            CandidateMatchEvaluation excellent = createEvaluationWithProbability(
                    "cand-1", "Get Lucky", 0.92, 0.0, 0.88
            );
            TrackMatchResult res1 = validatedDecisionEngine.decide("track-1", sourceTrack, List.of(excellent));
            assertThat(res1.confidence()).isEqualTo(MatchConfidence.EXCELLENT);

            // High score (0.92 >= 0.90) but moderate probability (0.75 < 0.85) -> GOOD
            CandidateMatchEvaluation good = createEvaluationWithProbability(
                    "cand-2", "Get Lucky", 0.92, 0.0, 0.75
            );
            TrackMatchResult res2 = validatedDecisionEngine.decide("track-1", sourceTrack, List.of(good));
            assertThat(res2.confidence()).isEqualTo(MatchConfidence.GOOD);
        }
    }

    @Nested
    @DisplayName("Validation and Contracts")
    class Validation {

        @Test
        @DisplayName("should throw NullPointerException when source track or evaluations list is null")
        void shouldThrowOnNullArguments() {
            assertThatThrownBy(() -> decisionEngine.decide("id", null, List.of()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> decisionEngine.decide("id", sourceTrack, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    private NormalizedTrackMetadata createSourceTrack(String title, String primaryArtist) {
        return new NormalizedTrackMetadata(
                RawTrackMetadata.of(title, primaryArtist, "Discovery", 248000L),
                title.toLowerCase(),
                new NormalizedArtistCredit(primaryArtist.toLowerCase(), List.of()),
                List.of(primaryArtist.toLowerCase()),
                List.of(),
                "discovery",
                Duration.ofMillis(248000),
                new TokenizedTitle(
                        title, title, false, Set.of(), null, null, Set.of(), List.of(), List.of(), List.of()
                )
        );
    }

    private CandidateMatchEvaluation createEvaluation(
            String id,
            String title,
            Double overallScore,
            Double versionPenalty
    ) {
        return createEvaluationWithProbability(id, title, overallScore, versionPenalty, null);
    }

    private CandidateMatchEvaluation createEvaluationWithProbability(
            String id,
            String title,
            Double overallScore,
            Double versionPenalty,
            Double calibratedProbability
    ) {
        DestinationTrackCandidate candidate = new DestinationTrackCandidate(
                id, title, "Daft Punk", "Description", "https://thumb.jpg", "2021-01-01", Duration.ofMinutes(4)
        );
        CandidateTrackFeatures features = new CandidateTrackFeatures(
                id, title, title.toLowerCase(), title, "Daft Punk", "daft punk",
                Duration.ofMinutes(4), 240000L, true, true, "Description", "2021-01-01",
                CandidateVersionEvidence.empty(), OfficialSignalEvidence.none(), candidate
        );
        MatchScoreBreakdown breakdown = MatchScoreBreakdown.builder()
                .titleScore(overallScore)
                .artistScore(1.0)
                .durationScore(1.0)
                .albumScore(1.0)
                .officialSignalScore(1.0)
                .versionPenalty(versionPenalty)
                .build();

        return CandidateMatchEvaluation.of(
                candidate,
                overallScore,
                calibratedProbability,
                MatchConfidence.GOOD,
                breakdown,
                List.of(),
                features
        );
    }
}
