package com.spotifyyoutube.migrator.migration.reliability.scheduling;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the durable PostgreSQL migration scheduler and worker dispatch (Phase 13D-B and 13D-C).
 */
@ConfigurationProperties(prefix = "migration.scheduler")
public class DurableSchedulerProperties {

    /**
     * Whether the periodic migration scheduler is enabled.
     */
    private boolean enabled = true;

    /**
     * Polling interval in milliseconds for discovering and claiming eligible migrations.
     */
    private long pollIntervalMs = 5000L;

    /**
     * Prefix for worker node/instance identification in lease records.
     */
    private String workerIdPrefix = "scheduler-node-";

    /**
     * Default lease duration in seconds assigned during atomic claims.
     */
    private long leaseDurationSeconds = 60L;

    /**
     * Deferred retry backoff in seconds assigned when executor rejects dispatch due to saturation.
     */
    private long rejectionBackoffSeconds = 10L;

    /**
     * Interval in milliseconds between worker lease heartbeat renewals.
     * Must be shorter than leaseDurationSeconds.
     */
    private long heartbeatIntervalMs = 15000L;

    /**
     * Grace period in seconds during application shutdown to allow in-flight tasks to complete.
     */
    private long shutdownGracePeriodSeconds = 30L;

    /**
     * Maximum number of claims executed in a single scheduler poll cycle.
     */
    private int maxClaimsPerPoll = 4;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public String getWorkerIdPrefix() {
        return workerIdPrefix;
    }

    public void setWorkerIdPrefix(String workerIdPrefix) {
        this.workerIdPrefix = workerIdPrefix;
    }

    public long getLeaseDurationSeconds() {
        return leaseDurationSeconds;
    }

    public void setLeaseDurationSeconds(long leaseDurationSeconds) {
        this.leaseDurationSeconds = leaseDurationSeconds;
    }

    public long getRejectionBackoffSeconds() {
        return rejectionBackoffSeconds;
    }

    public void setRejectionBackoffSeconds(long rejectionBackoffSeconds) {
        this.rejectionBackoffSeconds = rejectionBackoffSeconds;
    }

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public void setHeartbeatIntervalMs(long heartbeatIntervalMs) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    public long getShutdownGracePeriodSeconds() {
        return shutdownGracePeriodSeconds;
    }

    public void setShutdownGracePeriodSeconds(long shutdownGracePeriodSeconds) {
        this.shutdownGracePeriodSeconds = shutdownGracePeriodSeconds;
    }

    public int getMaxClaimsPerPoll() {
        return maxClaimsPerPoll;
    }

    public void setMaxClaimsPerPoll(int maxClaimsPerPoll) {
        this.maxClaimsPerPoll = maxClaimsPerPoll;
    }
}
