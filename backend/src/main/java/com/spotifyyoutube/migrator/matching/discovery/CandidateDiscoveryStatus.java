package com.spotifyyoutube.migrator.matching.discovery;

/**
 * Explicit outcome status for candidate discovery.
 * Distinguishes successful discovery, legitimate empty results, partial provider failures, and total failures.
 */
public enum CandidateDiscoveryStatus {
    /**
     * Discovery succeeded and found at least one candidate; all executed queries succeeded.
     */
    SUCCESS,

    /**
     * All executed queries succeeded, but zero candidates were found across all queries.
     * Represents a legitimate outcome (e.g., obscure or unlisted track) rather than a system failure.
     */
    EMPTY,

    /**
     * At least one candidate was discovered, but one or more executed queries failed (e.g., transient network or quota error).
     * Candidates from successful queries are preserved.
     */
    PARTIAL_FAILURE,

    /**
     * All executed queries failed (e.g., API quota exhausted, authentication failure, or network outage).
     * Zero candidates were discovered.
     */
    FAILED
}
