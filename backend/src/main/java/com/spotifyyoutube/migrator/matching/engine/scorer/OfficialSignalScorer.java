package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.SourceTrackFeatures;

/**
 * Strategy interface for evaluating official provenance signals for a destination candidate.
 * Produces a normalized score in {@code [0.0, 1.0]} or returns {@code null} if provenance
 * evidence cannot be evaluated.
 */
public interface OfficialSignalScorer {

    /**
     * Evaluates official provenance evidence for the candidate track.
     *
     * @param source source track features, must not be null
     * @param candidate candidate track features, must not be null
     * @return normalized provenance score in {@code [0.0, 1.0]} (1.0 for verified official sources, 0.0 otherwise)
     * @throws NullPointerException if source or candidate is null
     */
    Double score(SourceTrackFeatures source, CandidateTrackFeatures candidate);
}
