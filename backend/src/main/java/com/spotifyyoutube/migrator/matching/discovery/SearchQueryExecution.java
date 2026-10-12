package com.spotifyyoutube.migrator.matching.discovery;

import java.util.Objects;

/**
 * Records the execution outcome of an individual search query during candidate discovery.
 * Captures query metadata, candidate yield, success status, and error details for diagnostics.
 *
 * @param query the executed search query
 * @param resultCount number of candidates returned by this query
 * @param successful whether the provider query executed successfully
 * @param errorMessage error message if execution failed, or null if successful
 */
public record SearchQueryExecution(
        SearchQuery query,
        int resultCount,
        boolean successful,
        String errorMessage
) {
    public SearchQueryExecution {
        Objects.requireNonNull(query, "SearchQuery must not be null");
        if (resultCount < 0) {
            throw new IllegalArgumentException("Result count must not be negative");
        }
    }

    public static SearchQueryExecution success(SearchQuery query, int resultCount) {
        return new SearchQueryExecution(query, resultCount, true, null);
    }

    public static SearchQueryExecution failure(SearchQuery query, String errorMessage) {
        return new SearchQueryExecution(query, 0, false, errorMessage != null ? errorMessage : "Unknown search error");
    }
}
