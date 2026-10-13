package com.spotifyyoutube.migrator.matching.engine.feature;

import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;

import java.util.List;

/**
 * Service contract for deterministic candidate feature extraction.
 * Extracts comparison-ready features from normalized source metadata and destination candidates
 * without performing similarity scoring or applying numerical penalties.
 */
public interface TrackFeatureExtractor {

    /**
     * Extracts comparison features from normalized source track metadata.
     *
     * @param sourceTrack non-null normalized source track metadata
     * @return immutable source features
     */
    SourceTrackFeatures extractSourceFeatures(NormalizedTrackMetadata sourceTrack);

    /**
     * Extracts comparison features from normalized source track metadata with an explicit source identifier.
     *
     * @param sourceTrackId optional source track identifier (e.g. Spotify ID)
     * @param sourceTrack non-null normalized source track metadata
     * @return immutable source features
     */
    SourceTrackFeatures extractSourceFeatures(String sourceTrackId, NormalizedTrackMetadata sourceTrack);

    /**
     * Extracts comparison features from a destination candidate in isolation.
     *
     * @param candidate non-null destination track candidate
     * @return immutable candidate features
     */
    CandidateTrackFeatures extractCandidateFeatures(DestinationTrackCandidate candidate);

    /**
     * Extracts comparison features from a destination candidate in the context of the source track.
     * Allows evaluating artist-channel name equivalence and official provenance signals against the source.
     *
     * @param candidate non-null destination track candidate
     * @param sourceTrack optional source track metadata for provenance context
     * @return immutable candidate features
     */
    CandidateTrackFeatures extractCandidateFeatures(DestinationTrackCandidate candidate, NormalizedTrackMetadata sourceTrack);

    /**
     * Extracts comparison features for a collection of destination candidates in encounter order.
     *
     * @param candidates non-null list of destination candidates
     * @param sourceTrack optional source track metadata for provenance context
     * @return unmodifiable list of candidate features in encounter order
     */
    List<CandidateTrackFeatures> extractCandidateFeatures(List<DestinationTrackCandidate> candidates, NormalizedTrackMetadata sourceTrack);
}
