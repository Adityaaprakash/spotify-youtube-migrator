package com.spotifyyoutube.migrator.migration.application.execution.contract;

/**
 * Granular status outcome of attempting to insert an approved track into a destination playlist.
 */
public enum TrackInsertionStatus {

    /**
     * Confirmed successful insertion by the destination provider.
     */
    SUCCESS,

    /**
     * Confirmed provider rejection or unrecoverable error for this track.
     */
    FAILED,

    /**
     * Ambiguous outcome due to network timeout or connection reset; remote state cannot be verified.
     */
    UNCERTAIN,

    /**
     * Not attempted because a prior track encountered an unrecoverable failure or uncertain outcome,
     * preserving relative ordering invariants.
     */
    SKIPPED
}
