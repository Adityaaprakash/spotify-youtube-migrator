package com.spotifyyoutube.migrator.migration.reliability.policy;

/**
 * Structured outcome type of a retry policy evaluation (Phase 13C).
 */
public enum RetryDecisionType {

    /**
     * Failure is transient and retry is permitted within budget.
     */
    RETRY_PERMITTED,

    /**
     * Provider daily quota is exhausted; retry is deferred until the next daily quota window.
     */
    DEFERRED_QUOTA_RESET,

    /**
     * External mutating operation outcome is ambiguous after a transport error; requires reconciliation.
     */
    RECONCILIATION_REQUIRED,

    /**
     * Maximum retry attempts exhausted; operation must not be retried.
     */
    BUDGET_EXHAUSTED,

    /**
     * Failure is permanent and non-retryable.
     */
    NON_RETRYABLE,

    /**
     * Authentication failed or token expired; requires reauthorization before subsequent attempt.
     */
    REQUIRES_REAUTHORIZATION,

    /**
     * Provider Retry-After exceeds maximum excessive delay threshold; requires escalation.
     */
    EXCESSIVE_DELAY
}

