package com.spotifyyoutube.migrator.migration.progress.application;

import com.spotifyyoutube.migrator.migration.progress.domain.MigrationProgressSnapshot;

import java.util.UUID;

/**
 * Application service providing read-only progress tracking and per-track calculation for migration executions (Phase 12D).
 */
public interface MigrationProgressService {

    /**
     * Retrieves the current progress snapshot for the specified execution ID.
     * Enforces anti-IDOR user ownership and computes mathematically consistent progress from persisted state.
     *
     * @param executionId         the migration execution identifier
     * @param authenticatedUserId the authenticated user identifier
     * @return immutable progress snapshot
     */
    MigrationProgressSnapshot getProgress(UUID executionId, UUID authenticatedUserId);

    /**
     * Retrieves the current progress snapshot for the execution associated with the specified review session ID.
     * Enforces anti-IDOR user ownership and computes mathematically consistent progress from persisted state.
     *
     * @param reviewSessionId     the review session identifier
     * @param authenticatedUserId the authenticated user identifier
     * @return immutable progress snapshot
     */
    MigrationProgressSnapshot getProgressByReviewSession(UUID reviewSessionId, UUID authenticatedUserId);
}
