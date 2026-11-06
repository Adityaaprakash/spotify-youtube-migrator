package com.spotifyyoutube.migrator.migration.reliability.classification;

/**
 * Structured retry eligibility for provider failures (Phase 13B).
 * <p>
 * Signals downstream retry policies (Phase 13C) and durable schedulers (Phase 13D)
 * whether an operation may be retried, must be deferred, requires re-authentication,
 * or should be failed permanently without retry.
 */
public enum RetryEligibility {

    /**
     * Operation failed transiently and is eligible for automatic retry
     * (e.g. transient 5xx, standard 429, user rate limit 403).
     */
    RETRYABLE,

    /**
     * Permanent failure; retrying will produce the identical error without client modifications
     * (e.g. malformed request 400, not found 404, forbidden 403, unclassified failure).
     */
    NON_RETRYABLE,

    /**
     * Provider daily quota exhausted (e.g. YouTube 403 quotaExceeded).
     * Non-retryable within the current daily window; eligible for deferred scheduling after daily reset.
     */
    DEFERRED_RESET,

    /**
     * Credentials or token invalid/expired (e.g. 401 authError).
     * Requires token lifecycle refresh before any subsequent attempt.
     */
    REQUIRES_REAUTHORIZATION
}
