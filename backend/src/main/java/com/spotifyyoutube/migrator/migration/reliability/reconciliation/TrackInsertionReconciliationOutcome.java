package com.spotifyyoutube.migrator.migration.reliability.reconciliation;

/**
 * Outcome classifications for ambiguous track insertion reconciliation (Phase 13F).
 */
public enum TrackInsertionReconciliationOutcome {

    /**
     * Remote playlist inspection verified that the specific occurrence was successfully inserted.
     */
    VERIFIED_ADDED,

    /**
     * Bounded two-stage inspection confirmed that the occurrence was not inserted. Safe to retry if policy permits.
     */
    CONFIRMED_ABSENT,

    /**
     * Inconsistent ordering, extra remote occurrences, or duplicate remote item IDs detected. Fails closed.
     */
    INTEGRITY_VIOLATION,

    /**
     * Remote playlist inspection failed (provider 5xx, timeout, unparseable response).
     */
    INSPECTION_FAILED
}
