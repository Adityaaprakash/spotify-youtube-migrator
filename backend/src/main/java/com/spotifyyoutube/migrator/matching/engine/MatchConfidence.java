package com.spotifyyoutube.migrator.matching.engine;

/**
 * Qualitative confidence classification for evaluated candidate matches.
 * Kept strictly distinct from numerical scores and calibrated probabilities.
 */
public enum MatchConfidence {

    /**
     * Near-perfect correspondence across title, artist, duration, and provenance.
     */
    EXCELLENT,

    /**
     * Strong correspondence with minor, acceptable variations in formatting or duration.
     */
    GOOD,

    /**
     * Marginal or low-confidence correspondence with noticeable discrepancy or ambiguity.
     */
    POOR
}
