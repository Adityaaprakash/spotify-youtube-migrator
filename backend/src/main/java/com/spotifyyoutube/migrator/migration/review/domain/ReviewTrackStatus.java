package com.spotifyyoutube.migrator.migration.review.domain;

/**
 * Lifecycle and review decision status of a single track within a review session.
 * Kept strictly distinct from automatic matching classifications (such as AUTO_MATCH or REVIEW_REQUIRED).
 */
public enum ReviewTrackStatus {

    /**
     * Track requires user inspection or decision (e.g. ambiguous match, unmatched, failed, or duplicate conflict).
     */
    AWAITING_REVIEW,

    /**
     * Automatic candidate has been accepted (either high-confidence auto-match or user approval of automatic candidate).
     */
    ACCEPTED,

    /**
     * User has manually selected a replacement candidate.
     */
    USER_SELECTED,

    /**
     * Track has been explicitly excluded from migration by the user.
     */
    EXCLUDED
}
