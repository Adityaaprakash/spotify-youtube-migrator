package com.spotifyyoutube.migrator.matching.engine;

import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable output contract of the matching engine.
 * Represents the final migration decision for a source track, along with any selected destination candidate,
 * component score breakdown, ranked candidate evaluations, and explainable diagnostic reasons.
 *
 * @param sourceTrackId optional source track identifier (e.g. Spotify ID), or null
 * @param sourceTrack non-null normalized source track metadata
 * @param decision non-null final matching decision
 * @param selectedCandidate the chosen destination candidate, or null if no match or unevaluated
 * @param score composite numerical similarity score (0.0 to 1.0), or null if uncomputed
 * @param calibratedProbability estimated probability of true match (0.0 to 1.0), or null if uncalibrated
 * @param confidence qualitative confidence level, or null if unclassified
 * @param scoreBreakdown component score breakdown for the selected candidate, or null if uncomputed
 * @param candidateEvaluations unmodifiable list of evaluated candidates and their metrics
 * @param matchReasons unmodifiable list of explainable diagnostic reasons or notes
 */
public record TrackMatchResult(
        String sourceTrackId,
        NormalizedTrackMetadata sourceTrack,
        MatchDecision decision,
        DestinationTrackCandidate selectedCandidate,
        Double score,
        Double calibratedProbability,
        MatchConfidence confidence,
        MatchScoreBreakdown scoreBreakdown,
        List<CandidateMatchEvaluation> candidateEvaluations,
        List<String> matchReasons
) {
    public TrackMatchResult {
        Objects.requireNonNull(sourceTrack, "NormalizedTrackMetadata must not be null");
        Objects.requireNonNull(decision, "MatchDecision must not be null");
        sourceTrackId = sourceTrackId != null ? sourceTrackId.trim() : null;
        candidateEvaluations = candidateEvaluations != null ? List.copyOf(candidateEvaluations) : List.of();
        matchReasons = matchReasons != null ? List.copyOf(matchReasons) : List.of();

        // Ensure selected candidate consistency if evaluations are provided
        if (selectedCandidate != null && !candidateEvaluations.isEmpty()) {
            boolean found = candidateEvaluations.stream()
                    .anyMatch(eval -> eval.candidate() != null
                            && Objects.equals(eval.candidate().id(), selectedCandidate.id()));
            if (!found) {
                throw new IllegalArgumentException("Selected candidate must be present among candidate evaluations: "
                        + selectedCandidate.id());
            }
        }
    }

    /**
     * Factory method creating a clean NO_MATCH result.
     */
    public static TrackMatchResult noMatch(
            String sourceTrackId,
            NormalizedTrackMetadata sourceTrack,
            List<String> reasons
    ) {
        return new TrackMatchResult(
                sourceTrackId,
                sourceTrack,
                MatchDecision.NO_MATCH,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                reasons != null ? reasons : List.of()
        );
    }

    public static TrackMatchResult noMatch(NormalizedTrackMetadata sourceTrack, List<String> reasons) {
        return noMatch(null, sourceTrack, reasons);
    }

    /**
     * Factory method creating an unevaluated result from a {@link TrackMatchRequest}.
     */
    public static TrackMatchResult unevaluated(TrackMatchRequest request, String reason) {
        Objects.requireNonNull(request, "TrackMatchRequest must not be null");
        List<String> reasons = reason != null ? List.of(reason) : List.of();
        return new TrackMatchResult(
                request.sourceTrackId(),
                request.sourceTrack(),
                MatchDecision.NO_MATCH,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                reasons
        );
    }

    public boolean isMatch() {
        return decision == MatchDecision.MATCH;
    }

    public boolean isReviewRequired() {
        return decision == MatchDecision.REVIEW_REQUIRED;
    }

    public boolean isNoMatch() {
        return decision == MatchDecision.NO_MATCH;
    }

    public boolean hasSelectedCandidate() {
        return selectedCandidate != null;
    }

    public boolean hasScore() {
        return score != null;
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

    public Optional<DestinationTrackCandidate> getSelectedCandidate() {
        return Optional.ofNullable(selectedCandidate);
    }

    public Optional<Double> getScore() {
        return Optional.ofNullable(score);
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

    public Optional<String> getSourceTrackId() {
        return Optional.ofNullable(sourceTrackId);
    }
}
