package com.spotifyyoutube.migrator.migration.review.api.dto;

/**
 * Enumeration of supported review decisions for a track.
 */
public enum ReviewDecisionType {
    /**
     * Accept the automatically suggested destination candidate.
     */
    ACCEPT,

    /**
     * Select a specific destination candidate chosen by the reviewer.
     */
    SELECT_CANDIDATE,

    /**
     * Exclude the track from migration.
     */
    EXCLUDE
}
