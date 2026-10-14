package com.spotifyyoutube.migrator.matching.benchmark.runner;

/**
 * Reason an example was excluded from formal benchmark evaluation scoring.
 */
public enum EvaluationExclusionReason {

    /**
     * Example is eligible and included in formal scoring.
     */
    NONE,

    /**
     * Excluded because example status is {@code UNVERIFIED} (e.g. pending audit evidence).
     */
    UNVERIFIED_STATUS,

    /**
     * Excluded because example status is {@code DISPUTED} (conflicting ground-truth claims).
     */
    DISPUTED_STATUS,

    /**
     * Excluded because example split is not included in current evaluation run.
     */
    SPLIT_EXCLUDED,

    /**
     * Excluded because example category is not included in current evaluation run.
     */
    CATEGORY_EXCLUDED,

    /**
     * Excluded because candidate pool was required but unavailable or empty.
     */
    EMPTY_CANDIDATE_POOL,

    /**
     * Excluded due to malformed or incomplete source track metadata.
     */
    INVALID_SOURCE_METADATA,

    /**
     * Excluded due to matching engine execution failure or unexpected exception.
     */
    EXECUTION_FAILURE
}
