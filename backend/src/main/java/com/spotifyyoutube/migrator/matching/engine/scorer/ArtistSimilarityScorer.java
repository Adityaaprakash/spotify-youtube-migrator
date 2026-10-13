package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.SourceTrackFeatures;

/**
 * Strategy interface for calculating normalized artist similarity between
 * a normalized source Spotify track and a YouTube destination candidate.
 * <p>
 * Evaluates primary and featured artist alignments independently of title, duration,
 * or album dimensions, returning a normalized score in the range {@code [0.0, 1.0]}
 * or {@code null} when artist metadata is unavailable.
 */
public interface ArtistSimilarityScorer {

    /**
     * Calculates the normalized similarity score between the source track artists and candidate artists.
     *
     * @param source source track features, must not be null
     * @param candidate candidate track features, must not be null
     * @return normalized similarity score in range [0.0, 1.0], or null if artist metadata is missing/unavailable
     * @throws NullPointerException if source or candidate is null
     */
    Double score(SourceTrackFeatures source, CandidateTrackFeatures candidate);
}
