package com.spotifyyoutube.migrator.migration.execution.domain;

/**
 * Status of an individual track within a durable migration execution (Phase 11E).
 */
public enum ExecutionTrackStatus {

    /**
     * Track is approved and waiting to be inserted; eligible for execution.
     */
    PENDING,

    /**
     * Insertion is currently claimed and in-flight to the external music destination.
     * If found after a process restart, indicates an interrupted operation.
     */
    ADDING,

    /**
     * Provider confirmed successful insertion into the destination playlist.
     * Never re-inserted during resume.
     */
    ADDED,

    /**
     * Provider explicitly rejected the insertion with confirmed failure (e.g. video unavailable, private).
     */
    FAILED,

    /**
     * Remote outcome is ambiguous due to network timeout or connection reset (ResourceAccessException).
     * Must not be blindly retried to avoid duplicate tracks.
     */
    UNCERTAIN,

    /**
     * Skipped to preserve canonical playlist ordering following a prior failure or uncertain outcome.
     */
    SKIPPED,

    /**
     * Compensating deletion is currently claimed and in-flight to the external music destination.
     * Prevents concurrent duplicate deletions. If found after a process crash, indicates an interrupted rollback.
     */
    ROLLING_BACK,

    /**
     * Track was successfully inserted into destination and subsequently removed during a rollback (Phase 11F).
     */
    ROLLED_BACK;

    /**
     * Returns true if the track is in a final, settled state.
     */
    public boolean isSettled() {
        return this == ADDED || this == FAILED || this == UNCERTAIN || this == SKIPPED || this == ROLLED_BACK;
    }

    /**
     * Returns true if the track is eligible to be attempted for insertion.
     */
    public boolean isEligibleForAttempt() {
        return this == PENDING;
    }

    /**
     * Returns true if the track has been rolled back from the destination playlist.
     */
    public boolean isRolledBack() {
        return this == ROLLED_BACK;
    }
}
