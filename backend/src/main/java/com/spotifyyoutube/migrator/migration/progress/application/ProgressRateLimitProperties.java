package com.spotifyyoutube.migrator.migration.progress.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties governing progress endpoint polling limits and back-off behavior (Phase 12F).
 * <p>
 * Configurable under prefix {@code migration.progress.rate-limit}.
 * Default values allow up to 60 polling requests per minute per authenticated user,
 * accommodating active frontend client polling intervals of 1000ms–2000ms (1–2 seconds).
 */
@Component
@ConfigurationProperties(prefix = "migration.progress.rate-limit")
public class ProgressRateLimitProperties {

    /**
     * Whether progress API polling rate limiting is enabled.
     */
    private boolean enabled = true;

    /**
     * Maximum number of permitted progress requests within the time window.
     */
    private int maxRequests = 60;

    /**
     * Sliding time window duration in seconds.
     */
    private int windowSeconds = 60;

    /**
     * Recommended back-off duration in seconds returned in the {@code Retry-After} response header.
     */
    private int retryAfterSeconds = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxRequests() {
        return maxRequests;
    }

    public void setMaxRequests(int maxRequests) {
        if (maxRequests <= 0) {
            throw new IllegalArgumentException("maxRequests must be positive, got: " + maxRequests);
        }
        this.maxRequests = maxRequests;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("windowSeconds must be positive, got: " + windowSeconds);
        }
        this.windowSeconds = windowSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public void setRetryAfterSeconds(int retryAfterSeconds) {
        if (retryAfterSeconds <= 0) {
            throw new IllegalArgumentException("retryAfterSeconds must be positive, got: " + retryAfterSeconds);
        }
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
