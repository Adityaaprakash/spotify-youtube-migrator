package com.spotifyyoutube.migrator.migration.execution.repository;

import java.util.UUID;

/**
 * Custom extension interface for {@link ExecutionTrackRecordRepository} providing lock-first,
 * validate-second fenced track claim operations (Phase 13D-A).
 */
public interface ExecutionTrackRecordRepositoryCustom {

    /**
     * Atomically claims an individual track for active remote insertion processing (ADDING) under a fencing generation (Phase 13D-A).
     * Locks the parent migration_executions row in FOR SHARE mode first, validates lease_until > clock_timestamp()
     * in a separate SQL statement after lock acquisition, and mutates track to ADDING while retaining parent lock.
     *
     * @param trackId track record ID
     * @param executionId parent execution ID
     * @param fencingGeneration caller's acquired fencing token
     * @return 1 if claimed from PENDING, 0 if fencing token is stale, lease expired, or track was already claimed
     */
    int claimTrackAddingWithFencing(UUID trackId, UUID executionId, long fencingGeneration);

    /**
     * Atomically claims an individual track for rollback (ROLLING_BACK) under a fencing generation (Phase 13D-A).
     * Locks the parent migration_executions row in FOR SHARE mode first, validates lease_until > clock_timestamp()
     * in a separate SQL statement after lock acquisition, and mutates track to ROLLING_BACK while retaining parent lock.
     *
     * @param trackId track record ID
     * @param executionId parent execution ID
     * @param fencingGeneration caller's acquired fencing token
     * @return 1 if claimed from ADDED, 0 if fencing token is stale, lease expired, or track not ADDED
     */
    int claimTrackRollingBackWithFencing(UUID trackId, UUID executionId, long fencingGeneration);
}
