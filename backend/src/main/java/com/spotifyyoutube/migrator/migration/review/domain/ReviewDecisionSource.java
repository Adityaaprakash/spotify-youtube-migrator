package com.spotifyyoutube.migrator.migration.review.domain;

/**
 * Provenance of a review track decision, distinguishing automated machine outcomes from manual human actions.
 */
public enum ReviewDecisionSource {

    /**
     * Decision originates from the automated matching engine or default plan classification.
     */
    AUTOMATIC,

    /**
     * Decision originates from explicit manual user action (manual selection, approval, or exclusion).
     */
    USER_SELECTED
}
