package com.spotifyyoutube.migrator.migration.review.domain;

/**
 * Lifecycle status for a playlist migration review session.
 */
public enum ReviewSessionStatus {

    /**
     * Session is active and awaiting review resolutions.
     */
    IN_PROGRESS,

    /**
     * All required decisions have been resolved and the session has been finalized.
     */
    COMPLETED,

    /**
     * Session was cancelled or abandoned.
     */
    CANCELLED
}
