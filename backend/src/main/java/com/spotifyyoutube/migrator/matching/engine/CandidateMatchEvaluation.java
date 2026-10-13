package com.spotifyyoutube.migrator.matching.engine;

import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable evaluation result for an individual destination track candidate.
 * Encapsulates the candidate, its extracted comparison features, component score breakdown,
 * overall score, calibrated probability, and explainable diagnostic reasons.
 *
 * @param candidate non-null destination candidate being evaluated
 * @param overallScore composite numerical similarity score (0.0 to 1.0), or null if uncomputed
 * @param calibratedProbability estimated probability of true match (0.0 to 1.0), or null if uncalibrated
 * @param confidence qualitative confidence level, or null if unclassified
 * @param scoreBreakdown component score and penalty breakdown, or null if uncomputed
 * @param matchReasons unmodifiable list of explainable diagnostic reasons or notes
 * @param candidateFeatures comparison-ready features extracted from the candidate, or null if unextracted
 */
public record CandidateMatchEvaluation(
        DestinationTrackCandidate candidate,
        Double overallScore,
        Double calibratedProbability,
        MatchConfidence confidence,
        MatchScoreBreakdown scoreBreakdown,
        List<String> matchReasons,
        CandidateTrackFeatures candidateFeatures
) {
    public CandidateMatchEvaluation {
        Objects.requireNonNull(candidate, "DestinationTrackCandidate must not be null");
        matchReasons = matchReasons != null ? List.copyOf(matchReasons) : List.of();
    }

    /**
     * Creates an unevaluated candidate container holding candidate metadata and extracted features.
     */
    public static CandidateMatchEvaluation unevaluated(
            DestinationTrackCandidate candidate,
            CandidateTrackFeatures candidateFeatures
    ) {
        return new CandidateMatchEvaluation(
                candidate,
                null,
                null,
                null,
                null,
                List.of(),
                candidateFeatures
        );
    }

    public static CandidateMatchEvaluation of(
            DestinationTrackCandidate candidate,
            Double overallScore,
            MatchConfidence confidence,
            MatchScoreBreakdown breakdown,
            List<String> reasons,
            CandidateTrackFeatures features
    ) {
        return of(candidate, overallScore, null, confidence, breakdown, reasons, features);
    }

    public static CandidateMatchEvaluation of(
            DestinationTrackCandidate candidate,
            Double overallScore,
            Double calibratedProbability,
            MatchConfidence confidence,
            MatchScoreBreakdown breakdown,
            List<String> reasons,
            CandidateTrackFeatures features
    ) {
        return new CandidateMatchEvaluation(
                candidate,
                overallScore,
                calibratedProbability,
                confidence,
                breakdown,
                reasons,
                features
        );
    }

    public String candidateId() {
        return candidate.id();
    }

    public String candidateTitle() {
        return candidate.title();
    }

    public boolean hasOverallScore() {
        return overallScore != null;
    }

    public boolean hasCalibratedProbability() {
        return calibratedProbability != null;
    }

    public boolean hasConfidence() {
        return confidence != null;
    }

    public boolean hasScoreBreakdown() {
        return scoreBreakdown != null;
    }

    public boolean hasCandidateFeatures() {
        return candidateFeatures != null;
    }

    public Optional<Double> getOverallScore() {
        return Optional.ofNullable(overallScore);
    }

    public Optional<Double> getCalibratedProbability() {
        return Optional.ofNullable(calibratedProbability);
    }

    public Optional<MatchConfidence> getConfidence() {
        return Optional.ofNullable(confidence);
    }

    public Optional<MatchScoreBreakdown> getScoreBreakdown() {
        return Optional.ofNullable(scoreBreakdown);
    }

    public Optional<CandidateTrackFeatures> getCandidateFeatures() {
        return Optional.ofNullable(candidateFeatures);
    }
}
