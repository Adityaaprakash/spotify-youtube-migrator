package com.spotifyyoutube.migrator.matching.benchmark.runner;

/**
 * Structured taxonomy for error classification and diagnostic breakdown.
 */
public enum BenchmarkErrorCategory {

    /**
     * Correct automatic acceptance: predicted MATCH and selected candidate matches expected target.
     */
    CORRECT_MATCH,

    /**
     * Correct rejection: ground truth is NO_MATCH and engine predicted NO_MATCH.
     */
    CORRECT_NO_MATCH,

    /**
     * Ranking failure: expected target was present in candidate pool, but engine selected a different candidate.
     */
    WRONG_TARGET_SELECTED,

    /**
     * Retrieval / candidate recall failure: expected target was absent from the candidate pool.
     */
    TARGET_ABSENT_FROM_CANDIDATE_POOL,

    /**
     * False positive acceptance: ground truth is NO_MATCH, but engine accepted a destination candidate.
     */
    FALSE_ACCEPTANCE_OF_NO_MATCH,

    /**
     * False negative rejection: ground truth is MATCH and target was present, but engine predicted NO_MATCH.
     */
    FALSE_NO_MATCH_FOR_POSITIVE,

    /**
     * Human review required: engine produced REVIEW_REQUIRED decision due to safety gates, ambiguity, or version penalty.
     */
    REVIEW_REQUIRED,

    /**
     * Recording version mismatch: error or review driven by detected version discrepancy (e.g. cover/live mismatch).
     */
    VERSION_MISMATCH,

    /**
     * Artist or title confusion: low similarity score or incorrect selection driven by naming ambiguity.
     */
    ARTIST_TITLE_CONFUSION,

    /**
     * Missing or insufficient candidate evidence in the provided candidate pool.
     */
    MISSING_OR_INSUFFICIENT_CANDIDATES,

    /**
     * Pipeline execution failure: matching engine threw an exception during evaluation.
     */
    EXECUTION_FAILURE,

    /**
     * Excluded from formal metrics due to UNVERIFIED audit status (e.g. BM-0029).
     */
    EXCLUDED_UNVERIFIED,

    /**
     * Excluded from formal metrics due to DISPUTED audit status.
     */
    EXCLUDED_DISPUTED,

    /**
     * Excluded by evaluation policy (e.g. split or category filtering).
     */
    EXCLUDED_INELIGIBLE,

    /**
     * Ambiguous or unclassified error requiring human inspection.
     */
    UNKNOWN_ERROR;

    public boolean isSuccess() {
        return this == CORRECT_MATCH || this == CORRECT_NO_MATCH;
    }

    public boolean isExcluded() {
        return this == EXCLUDED_UNVERIFIED || this == EXCLUDED_DISPUTED || this == EXCLUDED_INELIGIBLE;
    }
}
