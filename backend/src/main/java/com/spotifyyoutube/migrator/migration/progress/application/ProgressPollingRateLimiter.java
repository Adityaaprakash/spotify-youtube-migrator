package com.spotifyyoutube.migrator.migration.progress.application;

import com.spotifyyoutube.migrator.common.exception.RateLimitExceededException;

import java.util.UUID;

/**
 * Contract governing rate limiting for progress inspection endpoints (Phase 12F).
 * <p>
 * Evaluates whether an authenticated user is permitted to poll migration progress
 * based on configured request allowances and sliding time windows.
 */
public interface ProgressPollingRateLimiter {

    /**
     * Checks if the specified user is permitted to perform a progress poll request.
     * Consumes one request token if permitted.
     *
     * @param userId the authenticated user's unique identifier
     * @throws RateLimitExceededException if the user has exceeded their configured polling quota
     */
    void checkLimit(UUID userId);
}
