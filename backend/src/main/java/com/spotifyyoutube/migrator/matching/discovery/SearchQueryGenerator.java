package com.spotifyyoutube.migrator.matching.discovery;

import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;

import java.util.List;

/**
 * Contract for generating deterministic search queries from normalized track metadata.
 * Produces an ordered list of complementary queries for YouTube candidate discovery.
 */
public interface SearchQueryGenerator {

    /**
     * Generates an ordered, deterministic list of search queries using default discovery options.
     *
     * @param trackMetadata non-null normalized track metadata
     * @return non-null, ordered list of search queries
     */
    default List<SearchQuery> generateQueries(NormalizedTrackMetadata trackMetadata) {
        return generateQueries(trackMetadata, DiscoveryOptions.defaults());
    }

    /**
     * Generates an ordered, deterministic list of search queries according to specified options.
     *
     * @param trackMetadata non-null normalized track metadata
     * @param options discovery options specifying allowed strategies and limits
     * @return non-null, ordered list of search queries
     */
    List<SearchQuery> generateQueries(NormalizedTrackMetadata trackMetadata, DiscoveryOptions options);
}
