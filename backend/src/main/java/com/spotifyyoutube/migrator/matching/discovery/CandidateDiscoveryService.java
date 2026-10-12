package com.spotifyyoutube.migrator.matching.discovery;

import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;

/**
 * Service contract for discovering YouTube candidates for a normalized track.
 * Orchestrates deterministic query generation, provider search dispatching,
 * candidate deduplication, quota-aware bounding, and structured result synthesis.
 */
public interface CandidateDiscoveryService {

    /**
     * Discovers candidates using the specified discovery request.
     *
     * @param request candidate discovery request containing normalized track and options
     * @return structured discovery result
     */
    CandidateDiscoveryResult discoverCandidates(CandidateDiscoveryRequest request);

    /**
     * Discovers candidates using default discovery options.
     *
     * @param trackMetadata normalized track metadata
     * @return structured discovery result
     */
    default CandidateDiscoveryResult discoverCandidates(NormalizedTrackMetadata trackMetadata) {
        return discoverCandidates(new CandidateDiscoveryRequest(trackMetadata));
    }

    /**
     * Discovers candidates using custom discovery options.
     *
     * @param trackMetadata normalized track metadata
     * @param options configurable options
     * @return structured discovery result
     */
    default CandidateDiscoveryResult discoverCandidates(NormalizedTrackMetadata trackMetadata, DiscoveryOptions options) {
        return discoverCandidates(new CandidateDiscoveryRequest(trackMetadata, options));
    }
}
