package com.spotifyyoutube.migrator.matching.benchmark;

/**
 * Expected matching outcome for a benchmark track example.
 * <p>
 * Distinguishes positive matches from legitimate non-matches where no suitable
 * destination candidate exists on YouTube.
 */
public enum BenchmarkExpectedOutcome {

    /**
     * A valid destination recording exists on YouTube and should be matched.
     */
    MATCH,

    /**
     * No valid destination recording exists on YouTube (e.g., unreleased track,
     * local file, deleted release, or licensing absence). The engine is expected
     * to produce a non-match decision.
     */
    NO_MATCH;

    /**
     * Indicates whether this outcome expects a positive match.
     *
     * @return true if a positive match is expected, false otherwise
     */
    public boolean isMatch() {
        return this == MATCH;
    }

    /**
     * Indicates whether this outcome expects a legitimate non-match.
     *
     * @return true if a non-match is expected, false otherwise
     */
    public boolean isNoMatch() {
        return this == NO_MATCH;
    }
}
