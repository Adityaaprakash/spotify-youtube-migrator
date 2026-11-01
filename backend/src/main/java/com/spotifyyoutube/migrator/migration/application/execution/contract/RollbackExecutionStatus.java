package com.spotifyyoutube.migrator.migration.application.execution.contract;

/**
 * Overall status of a rollback execution request (Phase 11F).
 */
public enum RollbackExecutionStatus {

    /**
     * All requested items were successfully rolled back.
     */
    SUCCESS,

    /**
     * Some items were rolled back, but one or more items failed.
     */
    PARTIAL_SUCCESS,

    /**
     * All requested rollback operations failed.
     */
    FAILED,

    /**
     * No operations performed (e.g. all requested items were already rolled back).
     */
    NO_OP
}
