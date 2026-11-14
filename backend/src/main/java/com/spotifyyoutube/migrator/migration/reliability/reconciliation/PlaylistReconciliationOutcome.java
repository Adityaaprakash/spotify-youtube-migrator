package com.spotifyyoutube.migrator.migration.reliability.reconciliation;

/**
 * Outcome classifications for destination playlist creation reconciliation (Phase 13F).
 */
public enum PlaylistReconciliationOutcome {

    /**
     * Case A: Exactly one playlist matching the persisted operation UUID marker was discovered.
     */
    EXACT_MATCH_FOUND,

    /**
     * Case B: Conclusively confirmed that no playlist with matching marker or conflicting title exists
     * after bounded two-stage verification grace period. Safe for retry with same operation UUID.
     */
    CONCLUSIVELY_ABSENT,

    /**
     * Case C: Title collision detected on user's destination account without matching correlation marker.
     * Fails closed into RECOVERY_REQUIRED to prevent accidental adoption or duplicate creation.
     */
    TITLE_COLLISION,

    /**
     * Case D: Multiple playlists discovered bearing the same operation correlation marker.
     * Integrity violation; fails closed into RECOVERY_REQUIRED.
     */
    MULTIPLE_MATCHING_MARKERS,

    /**
     * Case E: Provider communication, authentication, or permission failure during reconciliation.
     */
    PROVIDER_ERROR
}
