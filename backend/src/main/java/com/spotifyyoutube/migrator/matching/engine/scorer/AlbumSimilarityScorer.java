package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.SourceTrackFeatures;

/**
 * Strategy interface for evaluating album similarity between a source track and a candidate track.
 * Produces a normalized score in {@code [0.0, 1.0]} or returns {@code null} when album evidence
 * is absent or unusable on either side, adhering to honest missing-data semantics.
 */
public interface AlbumSimilarityScorer {

    /**
     * Evaluates album alignment between source and candidate track features.
     *
     * @param source source track features containing normalizedAlbum, must not be null
     * @param candidate candidate track features containing description or metadata, must not be null
     * @return normalized similarity score in {@code [0.0, 1.0]}, or {@code null} if album evidence is absent
     * @throws NullPointerException if source or candidate is null
     */
    Double score(SourceTrackFeatures source, CandidateTrackFeatures candidate);

    /**
     * Evaluates album alignment between two raw or normalized album title strings.
     *
     * @param sourceAlbum album title from source track, or null if absent
     * @param candidateAlbum album title from candidate track, or null if absent
     * @return normalized similarity score in {@code [0.0, 1.0]}, or {@code null} if either album is null or blank
     */
    Double score(String sourceAlbum, String candidateAlbum);
}
