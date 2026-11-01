package com.spotifyyoutube.migrator.migration.execution.domain;

/**
 * Lifecycle status of a durable migration execution (Phase 11E).
 */
public enum MigrationExecutionStatus {

    /**
     * Execution created and track records initialized as PENDING; ready to begin insertion.
     */
    INITIALIZED,

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
     */
    public boolean canResume() {
        return this == INITIALIZED || this == PARTIALLY_COMPLETED || this == RECOVERY_REQUIRED || this == PARTIALLY_ROLLED_BACK;
    }

    /**
     * Returns true if this execution is eligible for a rollback operation.
     */
    public boolean canRollback() {
        return this != RUNNING && this != ROLLED_BACK;
    }
}
