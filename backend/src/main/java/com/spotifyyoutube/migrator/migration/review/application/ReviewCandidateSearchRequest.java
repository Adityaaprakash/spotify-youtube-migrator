package com.spotifyyoutube.migrator.migration.review.application;

/**
 * Immutable request payload specifying search parameters for manual candidate discovery
 * for a track under review.
 *
 * @param query optional reviewer-supplied search text; when null, the system derives queries
 *              from the source track's normalized metadata
 * @param limit optional maximum number of candidates requested (between 1 and 50)
 */
public record ReviewCandidateSearchRequest(
        String query,
        Integer limit
) {

    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 50;
    public static final int MIN_LIMIT = 1;
    public static final int MAX_QUERY_LENGTH = 500;

    public ReviewCandidateSearchRequest {
        if (query != null && query.isBlank()) {
            throw new IllegalArgumentException("Search query must not be blank");
        }
        if (query != null && query.trim().length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Search query exceeds maximum limit of " + MAX_QUERY_LENGTH + " characters");
        }
        if (limit != null && (limit < MIN_LIMIT || limit > MAX_LIMIT)) {
            throw new IllegalArgumentException("limit must be between " + MIN_LIMIT + " and " + MAX_LIMIT);
        }
    }

    public static ReviewCandidateSearchRequest defaultSearch() {
        return new ReviewCandidateSearchRequest(null, null);
    }

    public static ReviewCandidateSearchRequest of(String query) {
        return new ReviewCandidateSearchRequest(query, null);
    }

    public static ReviewCandidateSearchRequest of(String query, Integer limit) {
        return new ReviewCandidateSearchRequest(query, limit);
    }

    public static ReviewCandidateSearchRequest ofLimit(int limit) {
        return new ReviewCandidateSearchRequest(null, limit);
    }

    public boolean isCustomQuery() {
        return query != null && !query.isBlank();
    }
}
