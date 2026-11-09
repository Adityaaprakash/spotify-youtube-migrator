package com.spotifyyoutube.migrator.migration.execution.repository;

import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link ExecutionTrackRecord}.
 */
@Repository
public interface ExecutionTrackRecordRepository extends JpaRepository<ExecutionTrackRecord, UUID>, ExecutionTrackRecordRepositoryCustom {

    List<ExecutionTrackRecord> findByExecutionIdOrderByTargetPositionAsc(UUID executionId);

    Optional<ExecutionTrackRecord> findByExecutionIdAndTargetPosition(UUID executionId, int targetPosition);

    List<ExecutionTrackRecord> findByExecutionIdAndStatus(UUID executionId, ExecutionTrackStatus status);

    /**
     * Atomically claims a track for rollback by transitioning status from ADDED to ROLLING_BACK.
     * Prevents concurrent duplicate remote DELETE requests across application instances.
     * Clears persistence context to prevent stale in-memory track status.
     *
     * @param id track record ID
     * @param now timestamp when rollback claim started
     * @return 1 if successfully claimed from ADDED, 0 if already ROLLING_BACK, ROLLED_BACK, or concurrent conflict
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ExecutionTrackRecord t SET " +
           "t.status = com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus.ROLLING_BACK, " +
           "t.startedAt = :now, " +
           "t.errorMessage = 'Compensating deletion in progress', " +
           "t.version = t.version + 1 " +
           "WHERE t.id = :id AND t.status = com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus.ADDED")
    int claimTrackRollingBack(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Aggregates track counts by status for a given execution ID using the database index (Phase 12D).
     * Avoids loading all track records into memory.
     *
     * @param executionId execution identifier
     * @return list of status counts for statuses present in the execution
     */
    @Query("SELECT new com.spotifyyoutube.migrator.migration.execution.repository.TrackStatusCount(t.status, COUNT(t)) " +
           "FROM ExecutionTrackRecord t " +
           "WHERE t.execution.id = :executionId " +
           "GROUP BY t.status")
    List<TrackStatusCount> countTracksByStatusForExecution(@Param("executionId") UUID executionId);

    /**
     * Finds pending tracks for an execution that are currently due for processing (Phase 13D-A).
     * Evaluates cutoff strictly against PostgreSQL CURRENT_TIMESTAMP.
     *
     * @param executionId execution identifier
     * @return list of due pending track records ordered by canonical target position
     */
    @Query("SELECT t FROM ExecutionTrackRecord t " +
           "WHERE t.execution.id = :executionId " +
           "AND t.status = com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus.PENDING " +
           "AND (t.nextAttemptAt IS NULL OR t.nextAttemptAt <= CURRENT_TIMESTAMP) " +
           "ORDER BY t.targetPosition ASC")
    List<ExecutionTrackRecord> findDuePendingTracks(@Param("executionId") UUID executionId);

    /**
     * Finds the earliest deferred retry timestamp among all pending tracks in an execution (Phase 13D-A).
     * Evaluates cutoff strictly against PostgreSQL CURRENT_TIMESTAMP.
     *
     * @param executionId execution identifier
     * @return earliest nextAttemptAt timestamp, or empty if none are deferred
     */
    @Query("SELECT MIN(t.nextAttemptAt) FROM ExecutionTrackRecord t " +
           "WHERE t.execution.id = :executionId " +
           "AND t.status = com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus.PENDING " +
           "AND t.nextAttemptAt IS NOT NULL " +
           "AND t.nextAttemptAt > CURRENT_TIMESTAMP")
    Optional<Instant> findEarliestDeferredRetry(@Param("executionId") UUID executionId);

    /**
     * Fenced status update on an individual track record with row-level serialization (Phase 13D-A).
     * Locks the parent migration_executions row in FOR SHARE mode to serialize against concurrent
     * lease reassignment transactions, and verifies that the caller's fencing generation matches.
     * Sets updated_at to PostgreSQL clock_timestamp().
     *
     * @param trackId track record ID
     * @param executionId parent execution ID
     * @param fencingGeneration caller's acquired fencing token
     * @param newStatus target track status name
     * @param playlistItemId destination playlist item ID (if confirmed)
     * @param errorMessage sanitized error message (if failed/uncertain)
     * @param completedAt completion timestamp (nullable)
     * @return 1 if updated, 0 if fencing token is stale or lease was claimed by newer generation
     */
    @Transactional
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = "WITH locked_exec AS (" +
           "    SELECT id, lease_generation " +
           "    FROM migration_executions " +
           "    WHERE id = :executionId " +
           "    FOR SHARE" +
           ") " +
           "UPDATE execution_track_records t " +
           "SET status = :newStatus, " +
           "    destination_playlist_item_id = :playlistItemId, " +
           "    error_message = :errorMessage, " +
           "    completed_at = CAST(:completedAt AS TIMESTAMP WITH TIME ZONE), " +
           "    updated_at = clock_timestamp(), " +
           "    version = t.version + 1 " +
           "FROM locked_exec m " +
           "WHERE t.id = :trackId " +
           "  AND t.execution_id = m.id " +
           "  AND m.lease_generation = :fencingGeneration",
           nativeQuery = true)
    int updateTrackStatusWithFencingNative(
            @Param("trackId") UUID trackId,
            @Param("executionId") UUID executionId,
            @Param("fencingGeneration") long fencingGeneration,
            @Param("newStatus") String newStatus,
            @Param("playlistItemId") String playlistItemId,
            @Param("errorMessage") String errorMessage,
            @Param("completedAt") Instant completedAt
    );

    default int updateTrackStatusWithFencing(
            UUID trackId,
            UUID executionId,
            long fencingGeneration,
            ExecutionTrackStatus newStatus,
            String playlistItemId,
            String errorMessage,
            Instant completedAt
    ) {
        return updateTrackStatusWithFencingNative(
                trackId,
                executionId,
                fencingGeneration,
                newStatus != null ? newStatus.name() : null,
                playlistItemId,
                errorMessage,
                completedAt
        );
    }

    /**
     * Fenced retry scheduling on an individual track record with row-level serialization (Phase 13D-A).
     * Locks the parent migration_executions row in FOR SHARE mode to serialize against concurrent
     * lease reassignment transactions, and verifies that the caller's fencing generation matches.
     * Enforces that the track is currently in ADDING or PENDING to prevent completed tracks from being retried.
     * Sets updated_at to PostgreSQL clock_timestamp().
     *
     * @param trackId track record ID
     * @param executionId parent execution ID
     * @param fencingGeneration caller's acquired fencing token
     * @param nextAttemptAt earliest timestamp when this track is eligible for retry
     * @param lastFailureClassification failure classification category
     * @param retryBackoffMs backoff delay in milliseconds
     * @return 1 if scheduled, 0 if fencing token is stale or track already completed
     */
    @Transactional
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = "WITH locked_exec AS (" +
           "    SELECT id, lease_generation " +
           "    FROM migration_executions " +
           "    WHERE id = :executionId " +
           "    FOR SHARE" +
           ") " +
           "UPDATE execution_track_records t " +
           "SET status = 'PENDING', " +
           "    next_attempt_at = CASE " +
           "        WHEN :retryBackoffMs IS NOT NULL AND :retryBackoffMs > 0 " +
           "        THEN clock_timestamp() + (:retryBackoffMs * INTERVAL '1 millisecond') " +
           "        ELSE CAST(:nextAttemptAt AS TIMESTAMP WITH TIME ZONE) " +
           "    END, " +
           "    last_failure_classification = :lastFailureClassification, " +
           "    retry_backoff_ms = :retryBackoffMs, " +
           "    started_at = NULL, " +
           "    updated_at = clock_timestamp(), " +
           "    version = t.version + 1 " +
           "FROM locked_exec m " +
           "WHERE t.id = :trackId " +
           "  AND t.execution_id = m.id " +
           "  AND t.status IN ('ADDING', 'PENDING') " +
           "  AND m.lease_generation = :fencingGeneration",
           nativeQuery = true)
    int scheduleTrackRetryWithFencing(
            @Param("trackId") UUID trackId,
            @Param("executionId") UUID executionId,
            @Param("fencingGeneration") long fencingGeneration,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("lastFailureClassification") String lastFailureClassification,
            @Param("retryBackoffMs") Long retryBackoffMs
    );
}
