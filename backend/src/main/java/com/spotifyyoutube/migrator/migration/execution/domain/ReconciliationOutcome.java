package com.spotifyyoutube.migrator.migration.execution.domain;

/**
 * Outcome of evaluating remote destination evidence during state reconciliation (Phase 11G).
 * <p>
 * Strictly evidence-driven. Ambiguity is preserved when evidence is insufficient.
 */
public enum ReconciliationOutcome {

    /**
     * Sufficient remote evidence confirms that the destination playlist contains the item inserted by this execution.
     * Track transitions to ADDED.
     */
    CONFIRMED_INSERTED,

    /**
     * Sufficient remote evidence confirms that the insertion was not applied to the destination playlist.
     * Track transitions to FAILED for controlled recovery.
     */
    CONFIRMED_NOT_INSERTED,

    /**
     * Complete remote inspection confirms that the target playlist item ID is absent from the playlist.
     * Track transitions to ROLLED_BACK.
     */
    CONFIRMED_DELETED,

    /**
     * Complete remote inspection confirms that the target playlist item ID is still present in the playlist.
     * Track is restored to ADDED.
     */
    CONFIRMED_PRESENT,

    /**
     * Remote evidence is contradictory, insufficient, or pre-dates the attempt.
     * Ambiguity is preserved; track remains UNCERTAIN and execution remains RECOVERY_REQUIRED.
     */
    AMBIGUOUS,

    /**
     * Remote provider inspection failed or was incomplete.
     * State is untouched; track remains UNCERTAIN.
     */
    INSPECTION_FAILED
}
