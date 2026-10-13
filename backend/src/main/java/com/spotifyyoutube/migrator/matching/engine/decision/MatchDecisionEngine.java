package com.spotifyyoutube.migrator.matching.engine.decision;

import com.spotifyyoutube.migrator.matching.engine.CandidateMatchEvaluation;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchResult;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;

import java.util.List;

/**
 * Strategy interface for making final migration match decisions.
 * Evaluates ranked candidate evaluations and produces an immutable {@link TrackMatchResult}.
 */
public interface MatchDecisionEngine {

    /**
     * Evaluates ranked candidates and produces the final matching decision and diagnostic explanation.
     *
     * @param sourceTrackId optional source track identifier (e.g. Spotify track ID), may be null
     * @param sourceTrack non-null normalized source track metadata
     * @param rankedEvaluations non-null list of candidates in ranked order
     * @return immutable result containing decision, selected candidate, score breakdown, and diagnostic reasons
     * @throws NullPointerException if sourceTrack or rankedEvaluations is null
     */
    TrackMatchResult decide(
            String sourceTrackId,
            NormalizedTrackMetadata sourceTrack,
            List<CandidateMatchEvaluation> rankedEvaluations
    );
}
