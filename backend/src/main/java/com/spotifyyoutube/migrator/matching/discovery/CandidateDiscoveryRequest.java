package com.spotifyyoutube.migrator.matching.discovery;

import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;

import java.util.Objects;

/**
 * Immutable input request contract for candidate discovery.
 * Encapsulates the normalized track metadata and discovery execution options.
 *
 * @param trackMetadata non-null normalized track metadata produced by Phase 5 normalization
 * @param options configurable options for quota management and candidate limits
 */
public record CandidateDiscoveryRequest(
        NormalizedTrackMetadata trackMetadata,
        DiscoveryOptions options
) {
    public CandidateDiscoveryRequest {
        Objects.requireNonNull(trackMetadata, "NormalizedTrackMetadata must not be null");
        options = (options != null) ? options : DiscoveryOptions.defaults();
    }

    public CandidateDiscoveryRequest(NormalizedTrackMetadata trackMetadata) {
        this(trackMetadata, DiscoveryOptions.defaults());
    }
}
