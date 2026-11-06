package com.spotifyyoutube.migrator.migration.reliability.classification;

/**
 * Structured categories for external provider failures (Phase 13B).
 * <p>
 * Provides granular categorization of provider HTTP and error payload responses,
 * distinguishing short-term user rate limits, daily quota exhaustion, authentication expiration,
 * resource non-existence, permanent bad requests, and transient infrastructure errors.
 */
public enum ProviderFailureCategory {

    /**
     * Standard HTTP 429 rate limit returned by provider.
     * Indicates high request frequency across the client or application.
     */
    RATE_LIMITED,

    /**
     * Provider-specific per-user rate limit (e.g. YouTube HTTP 403 with reason "userRateLimitExceeded"
     * or "rateLimitExceeded"). Short-term QPS limit; retryable with exponential backoff.
     */
    USER_RATE_LIMITED,

    /**
     * Provider daily or project quota exhaustion (e.g. YouTube HTTP 403 with reason "quotaExceeded").
     * Non-retryable within the current daily quota window (resets at Pacific midnight).
     */
    QUOTA_EXCEEDED,

    /**
     * Permission denied, missing OAuth scopes, or access forbidden
     * (e.g. YouTube HTTP 403 with reason "insufficientFilePermissions", "forbidden", or "insufficientPermissions").
     */
    PERMISSION_DENIED,

    /**
     * Authentication failure, expired access token, or invalid credentials
     * (e.g. HTTP 401 with reason "authError", "invalid_token", "invalidCredentials", or "tokenExpired").
     * Requires token refresh before any subsequent attempt.
     */
    AUTH_EXPIRED,

    /**
     * Target resource does not exist or has been deleted
     * (e.g. HTTP 404 with reason "videoNotFound" or "playlistNotFound").
     */
    NOT_FOUND,

    /**
     * Malformed or semantically invalid client request
     * (e.g. HTTP 400 with reason "badRequest" or "invalidParameter"). Confirmed non-retryable.
     */
    INVALID_REQUEST,

    /**
     * Transient upstream provider server failure
     * (e.g. HTTP 500, 502, 503 with reason "backendError" or service unavailable).
     */
    TRANSIENT_SERVER_ERROR,

    /**
     * Unclassified, unrecognized, or ambiguous provider failure.
     * Treated defensively as non-retryable by default.
     */
    UNKNOWN
}
