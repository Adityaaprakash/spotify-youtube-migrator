package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.SourceTrackFeatures;

/**
 * Strategy interface for calculating normalized title similarity between
 * a normalized source Spotify track and a YouTube destination candidate.
 * <p>
 * Evaluates title similarity independently of artist, duration, or album dimensions,
 * returning a normalized score in the range {@code [0.0, 1.0]} or {@code null}
 * when title metadata is unavailable.
 */
public interface TitleSimilarityScorer {

    /**
     * Calculates the normalized similarity score between the source track title and candidate title.
     *
     * @param source source track features, must not be null
     * @param candidate candidate track features, must not be null
     * @return normalized similarity score in range [0.0, 1.0], or null if title metadata is missing/unavailable
     * @throws NullPointerException if source or candidate is null
     */
    Double score(SourceTrackFeatures source, CandidateTrackFeatures candidate);
}
