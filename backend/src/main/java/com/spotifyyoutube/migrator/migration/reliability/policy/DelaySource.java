package com.spotifyyoutube.migrator.migration.reliability.policy;

/**
 * Provenance of the delay calculated for a retry attempt (Phase 13C).
 */
public enum DelaySource {

    /**
     * Delay was explicitly specified by the provider via an RFC 9110 Retry-After header.
     */
    PROVIDER_RETRY_AFTER,

    /**
     * Delay was locally generated using AWS Full Jitter exponential backoff.
     */
    FULL_JITTER_BACKOFF,

    /**
     * No delay applied (e.g. non-retryable failure, budget exhausted, or zero duration).
     */
    NONE
}
