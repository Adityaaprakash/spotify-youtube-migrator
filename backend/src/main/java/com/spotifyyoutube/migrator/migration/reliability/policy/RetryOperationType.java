package com.spotifyyoutube.migrator.migration.reliability.policy;

/**
 * Logical operation types evaluated by {@link RetryPolicy} (Phase 13C).
 * <p>
 * Distinguishes external mutating write operations (which have ambiguous outcomes upon network
 * timeout or connection drop) from idempotent read and search operations.
 */
public enum RetryOperationType {

    /**
     * Destination playlist creation on the external provider (YouTube Data API POST /playlists).
     * Mutating external operation: transport errors are ambiguous and must NOT be blindly retried.
     */
    DESTINATION_PLAYLIST_CREATION(true),

    /**
     * Adding an individual track to a destination playlist (YouTube Data API POST /playlistItems).
     * Mutating external operation: transport errors are ambiguous and must NOT be blindly retried.
     */
    TRACK_INSERTION(true),

    /**
     * Upstream provider read or search operations (e.g. YouTube search, playlist metadata fetch).
     * Idempotent read operation: safe to retry transient errors with backoff.
     */
    PROVIDER_READ(false);

    private final boolean mutating;

    RetryOperationType(boolean mutating) {
        this.mutating = mutating;
    }

    /**
     * Returns true if this operation performs an external mutation whose outcome
     * is ambiguous upon network timeout or 5xx server error.
     *
     * @return true if mutating
     */
    public boolean isMutating() {
        return mutating;
    }
}
