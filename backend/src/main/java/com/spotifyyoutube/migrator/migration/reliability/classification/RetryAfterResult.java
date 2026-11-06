package com.spotifyyoutube.migrator.migration.reliability.classification;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of parsing an HTTP Retry-After header (Phase 13B).
 * <p>
 * Distinguishes valid provider delays, absent headers, and malformed/unparseable values,
 * providing safe diagnostics without obscuring error context.
 *
 * @param status parsing status (VALID, MISSING, or INVALID)
 * @param duration parsed delay duration (present only when status is VALID)
 * @param rawHeader original header value supplied by the provider
 * @param failureReason diagnostic explanation if status is INVALID
 */
public record RetryAfterResult(
        RetryAfterStatus status,
        Duration duration,
        String rawHeader,
        String failureReason
) {

    public enum RetryAfterStatus {
        VALID,
        MISSING,
        INVALID
    }

    public RetryAfterResult {
        Objects.requireNonNull(status, "status must not be null");
    }

    /**
     * Creates a result for a successfully parsed valid Retry-After duration.
     *
     * @param duration parsed non-negative duration
     * @param rawHeader raw header value
     * @return valid RetryAfterResult
     */
    public static RetryAfterResult valid(Duration duration, String rawHeader) {
        Objects.requireNonNull(duration, "duration must not be null");
        return new RetryAfterResult(RetryAfterStatus.VALID, duration, rawHeader, null);
    }

    /**
     * Creates a result when the Retry-After header is missing or null.
     *
     * @return missing RetryAfterResult
     */
    public static RetryAfterResult missing() {
        return new RetryAfterResult(RetryAfterStatus.MISSING, null, null, null);
    }

    /**
     * Creates a result when the Retry-After header is present but invalid or malformed.
     *
     * @param rawHeader raw header value
     * @param failureReason diagnostic explanation of parsing failure
     * @return invalid RetryAfterResult
     */
    public static RetryAfterResult invalid(String rawHeader, String failureReason) {
        return new RetryAfterResult(RetryAfterStatus.INVALID, null, rawHeader, failureReason);
    }

    /**
     * Returns the parsed delay if valid.
     *
     * @return Optional containing duration if status is VALID, empty otherwise
     */
    public Optional<Duration> delay() {
        return status == RetryAfterStatus.VALID ? Optional.ofNullable(duration) : Optional.empty();
    }

    /**
     * Checks if the header was successfully parsed and valid.
     *
     * @return true if status is VALID
     */
    public boolean isValid() {
        return status == RetryAfterStatus.VALID;
    }

    /**
     * Checks if the header was absent.
     *
     * @return true if status is MISSING
     */
    public boolean isMissing() {
        return status == RetryAfterStatus.MISSING;
    }

    /**
     * Checks if the header was malformed or invalid.
     *
     * @return true if status is INVALID
     */
    public boolean isInvalid() {
        return status == RetryAfterStatus.INVALID;
    }
}
