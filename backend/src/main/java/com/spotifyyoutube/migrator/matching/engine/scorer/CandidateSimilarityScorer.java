package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.MatchScoreBreakdown;
import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.SourceTrackFeatures;

/**
 * Minimal integration seam for calculating component similarity scores between
 * a source track and a destination candidate.
 * <p>
 * Evaluates independent similarity scorers (Phase 7C Title, Phase 7D Artist) and
 * populates a {@link MatchScoreBreakdown} while preserving missing-data semantics
 * and leaving future scores (duration, album, official signals, composite score) uncomputed.
 */
public interface CandidateSimilarityScorer {

    /**
     * Calculates component similarity scores for the given candidate against source features.
     *
     * @param source source track comparison features, must not be null
     * @param candidate candidate track comparison features, must not be null
     * @return MatchScoreBreakdown with calculated title and artist scores
     * @throws NullPointerException if source or candidate is null
     */
    MatchScoreBreakdown scoreCandidate(SourceTrackFeatures source, CandidateTrackFeatures candidate);
}
