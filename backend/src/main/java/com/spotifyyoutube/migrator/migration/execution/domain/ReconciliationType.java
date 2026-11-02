package com.spotifyyoutube.migrator.migration.execution.domain;

/**
 * Type of operation being reconciled during destination remote state verification (Phase 11G).
 */
public enum ReconciliationType {

    /**
     * Reconciling an uncertain track insertion attempt.
     */
    INSERTION,

    /**
     * Reconciling an uncertain track deletion / rollback attempt.
     */
    DELETION,

    /**
     * Reconciling a stale in-flight ADDING operation abandoned after process crash or lease expiry.
     */
    STALE_IN_FLIGHT_ADDING,

    /**
     * Reconciling a stale in-flight ROLLING_BACK operation abandoned after process crash or lease expiry.
     */
    STALE_IN_FLIGHT_ROLLING_BACK
}
