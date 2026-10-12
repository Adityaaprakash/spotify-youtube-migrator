package com.spotifyyoutube.migrator.matching.discovery;

import java.util.Objects;

/**
 * Immutable value representation of a single search query targeted at YouTube candidate discovery.
 *
 * @param query the non-blank, trimmed search query string (max 500 characters)
 * @param strategy the strategy that formulated this query
 * @param priority execution priority (1 = highest priority)
 */
public record SearchQuery(
        String query,
        SearchQueryStrategy strategy,
        int priority
) {
    public static final int MAX_QUERY_LENGTH = 500;

    public SearchQuery {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query must not be blank");
        }
        query = query.trim();
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Search query exceeds maximum limit of " + MAX_QUERY_LENGTH + " characters");
        }
        Objects.requireNonNull(strategy, "SearchQueryStrategy must not be null");
        if (priority < 1) {
            throw new IllegalArgumentException("Priority must be at least 1");
        }
    }

    public SearchQuery(String query, SearchQueryStrategy strategy) {
        this(query, strategy, 1);
    }
}
