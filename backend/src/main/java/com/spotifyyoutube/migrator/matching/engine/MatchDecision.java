package com.spotifyyoutube.migrator.matching.engine;

/**
 * High-level decision vocabulary produced by the matching engine.
 * Represents the migration suitability classification for a source track and its prospective candidates.
 */
public enum MatchDecision {

    /**
     * High-confidence match. The candidate reliably corresponds to the source track
     * and meets all automated migration thresholds without requiring manual user intervention.
     */
    MATCH,

    /**
     * Ambiguous or borderline match. The candidate exhibits plausible similarity,
     * but potential discrepancies (such as duration variance, recording version ambiguity,
     * or close competing candidates) require human review before migration.
     */
    REVIEW_REQUIRED,

    /**
     * No acceptable match found. Either no candidates were discovered, or all candidates
     * failed minimum similarity thresholds.
     */
    NO_MATCH
}
