package com.spotifyyoutube.migrator.migration.execution.domain;

/**
 * Lifecycle status of a durable migration execution (Phase 11E).
 */
public enum MigrationExecutionStatus {

    /**
     * Playlist creation operation intent recorded; awaiting external destination playlist creation (Phase 13F).
     */
    ALLOCATING_DESTINATION,

    /**
     * Execution created and track records initialized as PENDING; ready to begin insertion.
     */
    INITIALIZED,

    /**
     * Execution accepted and queued for asynchronous background processing (Phase 12B).
     * Persisted in database and waiting for worker claiming.
     */
    QUEUED,

    /**
     * Execution actively claimed by a worker thread and performing track insertions.
     * Prevents concurrent execution attempts.
     */
    RUNNING,

    /**
     * Execution stopped or completed with partial successes, failures, or skipped tracks.
     * Eligible for safe resume of remaining pending tracks.
     */
    PARTIALLY_COMPLETED,

    /**
     * Terminal state: all approved tracks have been successfully inserted into the destination playlist.
     */
    COMPLETED,

    /**
     * Terminal failure: zero tracks were successfully inserted and execution halted on failure.
     */
    FAILED,

    /**
     * Interrupted or ambiguous execution detected (e.g. crash during ADDING or UNCERTAIN timeout),
     * requiring reconciliation or review before proceeding.
     */
    RECOVERY_REQUIRED,

    /**
     * Terminal state: all successfully inserted tracks have been completely rolled back from the destination (Phase 11F).
     */
    ROLLED_BACK,

    /**
     * Execution where some inserted tracks were rolled back while others remain or failed (Phase 11F).
     */
    PARTIALLY_ROLLED_BACK;

    /**
     * Returns true if this status represents a terminal state where no further execution is permitted.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == ROLLED_BACK;
    }

    /**
     * Returns true if this execution can safely be resumed to process pending tracks.
     * Strictly limited to stopped/partial executions; QUEUED executions are waiting for initial dispatch.
     */
    public boolean canResume() {
        return this == INITIALIZED || this == PARTIALLY_COMPLETED || this == PARTIALLY_ROLLED_BACK;
    }

    /**
     * Returns true if this execution is eligible for a rollback operation.
     */
    public boolean canRollback() {
        return this != RUNNING && this != QUEUED && this != ROLLED_BACK && this != ALLOCATING_DESTINATION;
    }

    /**
     * Returns true if this execution is in QUEUED status waiting for background worker claiming (Phase 12B).
     */
    public boolean isQueued() {
        return this == QUEUED;
    }
}
