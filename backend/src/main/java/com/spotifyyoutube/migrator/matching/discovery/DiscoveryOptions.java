package com.spotifyyoutube.migrator.matching.discovery;

import java.util.Set;

/**
 * Configurable options for candidate discovery execution and YouTube API quota management.
 *
 * @param maxCandidates maximum total unique candidates to return (1 to 50, default 20)
 * @param maxQueries maximum number of search queries to execute per track (1 to 5, default 2)
 * @param resultsPerQuery maximum results requested per individual search query (1 to 50, default 10)
 * @param enabledStrategies set of allowed query generation strategies (default: all strategies)
 */
public record DiscoveryOptions(
        int maxCandidates,
        int maxQueries,
        int resultsPerQuery,
        Set<SearchQueryStrategy> enabledStrategies
) {
    public static final int DEFAULT_MAX_CANDIDATES = 20;
    public static final int DEFAULT_MAX_QUERIES = 2;
    public static final int DEFAULT_RESULTS_PER_QUERY = 10;
    public static final int MIN_LIMIT = 1;
    public static final int MAX_CANDIDATE_LIMIT = 50;
    public static final int MAX_QUERIES_LIMIT = 5;

    public DiscoveryOptions {
        if (maxCandidates < MIN_LIMIT || maxCandidates > MAX_CANDIDATE_LIMIT) {
            throw new IllegalArgumentException("maxCandidates must be between " + MIN_LIMIT + " and " + MAX_CANDIDATE_LIMIT);
        }
        if (maxQueries < MIN_LIMIT || maxQueries > MAX_QUERIES_LIMIT) {
            throw new IllegalArgumentException("maxQueries must be between " + MIN_LIMIT + " and " + MAX_QUERIES_LIMIT);
        }
        if (resultsPerQuery < MIN_LIMIT || resultsPerQuery > MAX_CANDIDATE_LIMIT) {
            throw new IllegalArgumentException("resultsPerQuery must be between " + MIN_LIMIT + " and " + MAX_CANDIDATE_LIMIT);
        }
        enabledStrategies = (enabledStrategies != null && !enabledStrategies.isEmpty())
                ? Set.copyOf(enabledStrategies)
                : Set.of(SearchQueryStrategy.values());
    }

    public static DiscoveryOptions defaults() {
        return new DiscoveryOptions(
                DEFAULT_MAX_CANDIDATES,
                DEFAULT_MAX_QUERIES,
                DEFAULT_RESULTS_PER_QUERY,
                Set.of(SearchQueryStrategy.values())
        );
    }
}
