package com.spotifyyoutube.migrator.common.exception;

/**
 * Exception thrown when a client exceeds configured API rate or polling limits (Phase 12F).
 * <p>
 * Distinct from internal worker pool saturation ({@link org.springframework.core.task.TaskRejectedException}):
 * this exception indicates excessive client request frequency and carries a client back-off duration.
 */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
