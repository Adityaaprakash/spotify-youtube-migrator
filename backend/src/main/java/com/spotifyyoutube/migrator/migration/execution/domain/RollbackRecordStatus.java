package com.spotifyyoutube.migrator.migration.execution.domain;

/**
 * Status of an individual track's rollback operation (Phase 11F).
 */
public enum RollbackRecordStatus {

    /**
     * Playlist item was successfully deleted from destination provider.
     */
    ROLLED_BACK,

    /**
     * Item was already in rolled back status on caller request; no duplicate remote call was made.
     */
    ALREADY_ROLLED_BACK,

    /**
     * Remote deletion failed or was rejected by the destination provider.
     */
    FAILED,

    /**
     * Remote deletion outcome is ambiguous (e.g. network timeout or HTTP 500/503 during deletion).
     * Destination playlist state is uncertain and must be reconciled before further operations.
     */
    UNCERTAIN
}
