package com.spotifyyoutube.migrator.migration.reliability.classification;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Structured classification result produced by {@link ProviderFailureClassifier} (Phase 13B).
 * <p>
 * Encapsulates the failure category, HTTP status code, provider error reason,
 * retry eligibility, parsed Retry-After delay (if present), and sanitized diagnostic details.
 *
 * @param category structured failure category
 * @param httpStatus upstream HTTP status code, if available
 * @param providerReason provider-specific error reason (e.g., "quotaExceeded", "userRateLimitExceeded")
 * @param retryEligibility structured retry eligibility for downstream retry policies
 * @param retryAfter parsed Retry-After duration, if supplied by provider and valid
 * @param diagnosticMessage sanitized diagnostic message, free of credentials or tokens
 */
public record ProviderFailureClassification(
        ProviderFailureCategory category,
        Integer httpStatus,
        String providerReason,
        RetryEligibility retryEligibility,
        Optional<Duration> retryAfter,
        String diagnosticMessage
) {

    public ProviderFailureClassification {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(retryEligibility, "retryEligibility must not be null");
        retryAfter = retryAfter != null ? retryAfter : Optional.empty();
        diagnosticMessage = diagnosticMessage != null ? diagnosticMessage : "Unknown provider failure";
    }

    /**
     * Checks if this failure is eligible for automatic retry.
     *
     * @return true if retryEligibility is RETRYABLE
     */
    public boolean isRetryEligible() {
        return retryEligibility == RetryEligibility.RETRYABLE;
    }

    /**
     * Checks if this failure is caused by provider quota exhaustion.
     *
     * @return true if category is QUOTA_EXCEEDED
     */
    public boolean isQuotaExceeded() {
        return category == ProviderFailureCategory.QUOTA_EXCEEDED;
    }

    /**
     * Checks if this failure is caused by short-term per-user rate limiting.
     *
     * @return true if category is USER_RATE_LIMITED
     */
    public boolean isUserRateLimited() {
        return category == ProviderFailureCategory.USER_RATE_LIMITED;
    }

    /**
     * Checks if this failure is caused by any rate-limit (standard 429 or user-level 403).
     *
     * @return true if category is RATE_LIMITED or USER_RATE_LIMITED
     */
    public boolean isRateLimited() {
        return category == ProviderFailureCategory.RATE_LIMITED || category == ProviderFailureCategory.USER_RATE_LIMITED;
    }

    /**
     * Checks if this failure is caused by expired credentials or missing tokens.
     *
     * @return true if category is AUTH_EXPIRED
     */
    public boolean isAuthExpired() {
        return category == ProviderFailureCategory.AUTH_EXPIRED;
    }

    /**
     * Checks if this failure is caused by non-existent resource.
     *
     * @return true if category is NOT_FOUND
     */
    public boolean isNotFound() {
        return category == ProviderFailureCategory.NOT_FOUND;
    }

    /**
     * Checks if this failure is caused by a transient provider server error (500, 502, 503).
     *
     * @return true if category is TRANSIENT_SERVER_ERROR
     */
    public boolean isTransientServerError() {
        return category == ProviderFailureCategory.TRANSIENT_SERVER_ERROR;
    }

    /**
     * Checks if this failure is caused by a permanent malformed request (400).
     *
     * @return true if category is INVALID_REQUEST
     */
    public boolean isInvalidRequest() {
        return category == ProviderFailureCategory.INVALID_REQUEST;
    }

    /**
     * Checks if this failure is caused by insufficient permissions (403 forbidden).
     *
     * @return true if category is PERMISSION_DENIED
     */
    public boolean isPermissionDenied() {
        return category == ProviderFailureCategory.PERMISSION_DENIED;
    }

    /**
     * Checks if this failure is unclassified or unknown.
     *
     * @return true if category is UNKNOWN
     */
    public boolean isUnknown() {
        return category == ProviderFailureCategory.UNKNOWN;
    }
}
