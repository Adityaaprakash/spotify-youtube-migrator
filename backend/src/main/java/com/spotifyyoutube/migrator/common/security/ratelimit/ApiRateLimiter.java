package com.spotifyyoutube.migrator.common.security.ratelimit;

import com.spotifyyoutube.migrator.common.exception.RateLimitExceededException;

/**
 * Interface defining operations for API rate limiting (Phase 16D).
 */
public interface ApiRateLimiter {

    /**
     * Evaluates whether a request with the specified client key is permitted under the given policy.
     * Consumes one token if permitted; throws {@link RateLimitExceededException} if quota exceeded.
     *
     * @param clientKey identifier for the rate limit subject (e.g. IP or user ID)
     * @param policy policy defining limits and sliding window
     * @param operationName description of the operation for logging and diagnostics
     * @throws RateLimitExceededException if quota is exhausted
     */
    void checkLimit(String clientKey, RateLimitProperties.PolicyProperties policy, String operationName);

    /**
     * Bounded, thread-safe cleanup of inactive/expired quota entries to prevent memory leaks.
     *
     * @return number of evicted inactive keys
     */
    int cleanupExpiredEntries();

    /**
     * Returns the count of currently tracked keys in memory.
     *
     * @return tracked key count
     */
    int getTrackedKeyCount();

    /**
     * Clears tracked in-memory quotas. Useful for test teardown.
     */
    void reset();
}
