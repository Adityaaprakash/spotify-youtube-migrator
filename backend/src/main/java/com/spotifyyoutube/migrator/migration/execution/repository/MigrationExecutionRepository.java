package com.spotifyyoutube.migrator.migration.execution.repository;

import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link MigrationExecution}.
 */
@Repository
public interface MigrationExecutionRepository extends JpaRepository<MigrationExecution, UUID>, MigrationExecutionRepositoryCustom {

    Optional<MigrationExecution> findByReviewSessionId(UUID reviewSessionId);

    Optional<MigrationExecution> findByPlaylistCreationOperationId(UUID playlistCreationOperationId);

    Optional<MigrationExecution> findByIdAndUserId(UUID id, UUID userId);

    List<MigrationExecution> findByUserIdOrderByCreatedAtDesc(UUID userId);

    boolean existsByReviewSessionId(UUID reviewSessionId);

    List<MigrationExecution> findByStatus(MigrationExecutionStatus status);

    List<MigrationExecution> findByStatusOrderByCreatedAtAsc(MigrationExecutionStatus status);

    /**
     * Atomically claims execution for processing if current status is in eligibleStatuses and version matches expectedVersion.
     * Safely increments version and clears the persistence context to avoid stale cached entities.
     * Guaranteed cross-instance atomic claiming backed by PostgreSQL row-level conditional locking.
     *
     * @param id execution ID
     * @param eligibleStatuses allowed source statuses
     * @param expectedVersion expected optimistic locking version
     * @param now current timestamp
     * @return 1 if successfully claimed, 0 if already claimed, status ineligible, or version stale
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MigrationExecution m SET m.status = com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus.RUNNING, " +
           "m.updatedAt = :now, m.version = m.version + 1 " +
           "WHERE m.id = :id AND m.status IN :eligibleStatuses AND m.version = :expectedVersion")
    int claimExecutionRunningWithVersion(
            @Param("id") UUID id,
            @Param("eligibleStatuses") Collection<MigrationExecutionStatus> eligibleStatuses,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") Instant now
    );

    /**
     * Atomically claims execution for processing if current status is in eligibleStatuses.
     *
     * @param id execution ID
     * @param eligibleStatuses allowed source statuses
     * @param now current timestamp
     * @return 1 if successfully claimed, 0 if status is not in eligibleStatuses or not found
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MigrationExecution m SET m.status = com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus.RUNNING, " +
           "m.updatedAt = :now, m.version = m.version + 1 " +
           "WHERE m.id = :id AND m.status IN :eligibleStatuses")
    int claimExecutionRunning(
            @Param("id") UUID id,
            @Param("eligibleStatuses") Collection<MigrationExecutionStatus> eligibleStatuses,
            @Param("now") Instant now
    );

    /**
     * Atomically claims execution for processing by updating status to RUNNING if not already RUNNING, COMPLETED, or ROLLED_BACK.
     *
     * @param id execution ID
     * @param now current timestamp
     * @return 1 if successfully claimed, 0 if already RUNNING/terminal or not found
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MigrationExecution m SET m.status = com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus.RUNNING, " +
           "m.updatedAt = :now, m.version = m.version + 1 " +
           "WHERE m.id = :id AND m.status NOT IN (" +
           "com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus.RUNNING, " +
           "com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus.COMPLETED, " +
           "com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus.ROLLED_BACK)")
    int claimExecutionRunning(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Conditionally renews the heartbeat and lease expiration using PostgreSQL database wall-clock time (Phase 13D-A).
     * Strictly requires matching workerId, fencing leaseGeneration, and an active (unexpired) lease.
     * Sets lease_until, heartbeat_at, and updated_at exclusively via PostgreSQL clock_timestamp().
     *
     * @param id execution ID
     * @param workerId worker identity
     * @param leaseGeneration expected fencing token
     * @param leaseDurationSeconds extension duration in seconds
     * @return 1 if renewed, 0 if lease ownership, generation, or expiry check failed
     */
    @Transactional
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = "UPDATE migration_executions " +
           "SET lease_until = clock_timestamp() + (:leaseDurationSeconds * INTERVAL '1 second'), " +
           "    heartbeat_at = clock_timestamp(), " +
           "    updated_at = clock_timestamp() " +
           "WHERE id = :id " +
           "  AND status = 'RUNNING' " +
           "  AND lease_owner = :workerId " +
           "  AND lease_generation = :leaseGeneration " +
           "  AND lease_until > clock_timestamp()",
           nativeQuery = true)
    int renewLeaseHeartbeat(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("leaseGeneration") long leaseGeneration,
            @Param("leaseDurationSeconds") long leaseDurationSeconds
    );


    /**
     * Conditionally updates execution status protected by fencing generation using PostgreSQL database wall-clock time (Phase 13D-A).
     *
     * @param id execution ID
     * @param workerId worker identity
     * @param leaseGeneration expected fencing token
     * @param newStatus target status
     * @return 1 if updated, 0 if lease ownership or generation is stale
     */
    @Transactional
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = "UPDATE migration_executions " +
           "SET status = :newStatus, " +
           "    updated_at = clock_timestamp() " +
           "WHERE id = :id " +
           "  AND lease_owner = :workerId " +
           "  AND lease_generation = :leaseGeneration " +
           "  AND lease_until IS NOT NULL " +
           "  AND lease_until > clock_timestamp()",
           nativeQuery = true)
    int updateStatusWithFencing(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("leaseGeneration") long leaseGeneration,
            @Param("newStatus") String newStatus
    );

    default int updateStatusWithFencing(
            UUID id,
            String workerId,
            long leaseGeneration,
            MigrationExecutionStatus newStatus
    ) {
        return updateStatusWithFencing(id, workerId, leaseGeneration, newStatus != null ? newStatus.name() : null);
    }

    /**
     * Atomically confirms created destination playlist with fencing verification and unexpired lease check (Phase 13G).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE migration_executions " +
           "SET destination_playlist_id = :destinationPlaylistId, " +
           "    destination_playlist_url = :destinationPlaylistUrl, " +
           "    status = :nextStatus, " +
           "    updated_at = clock_timestamp(), " +
           "    version = version + 1 " +
           "WHERE id = :executionId " +
           "  AND status IN ('ALLOCATING_DESTINATION', 'RUNNING') " +
           "  AND lease_owner = :workerId " +
           "  AND lease_generation = :leaseGeneration " +
           "  AND lease_until IS NOT NULL " +
           "  AND lease_until > clock_timestamp()",
           nativeQuery = true)
    int confirmDestinationPlaylistWithFencing(
            @Param("executionId") UUID executionId,
            @Param("destinationPlaylistId") String destinationPlaylistId,
            @Param("destinationPlaylistUrl") String destinationPlaylistUrl,
            @Param("nextStatus") String nextStatus,
            @Param("workerId") String workerId,
            @Param("leaseGeneration") long leaseGeneration
    );

    /**
     * Records a destination playlist creation failure code with fencing verification and unexpired lease check (Phase 13G).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE migration_executions " +
           "SET playlist_creation_failure_code = :failureCode, " +
           "    status = :nextStatus, " +
           "    updated_at = clock_timestamp(), " +
           "    version = version + 1 " +
           "WHERE id = :executionId " +
           "  AND status IN ('ALLOCATING_DESTINATION', 'RUNNING') " +
           "  AND lease_owner = :workerId " +
           "  AND lease_generation = :leaseGeneration " +
           "  AND lease_until IS NOT NULL " +
           "  AND lease_until > clock_timestamp()",
           nativeQuery = true)
    int recordPlaylistCreationFailureWithFencing(
            @Param("executionId") UUID executionId,
            @Param("failureCode") String failureCode,
            @Param("nextStatus") String nextStatus,
            @Param("workerId") String workerId,
            @Param("leaseGeneration") long leaseGeneration
    );

    /**
     * Atomically claims an execution for rollback, recording durable rollback intent,
     * assigning worker lease ownership, incrementing fencing generation, and setting parent status to RUNNING (Phase 13G-D).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE migration_executions " +
           "SET status = 'RUNNING', " +
           "    rollback_requested_at = clock_timestamp(), " +
           "    rollback_target_positions = :targetPositions, " +
           "    lease_owner = :workerId, " +
           "    lease_generation = lease_generation + 1, " +
           "    lease_until = clock_timestamp() + (:leaseDurationSeconds * INTERVAL '1 second'), " +
           "    heartbeat_at = clock_timestamp(), " +
           "    updated_at = clock_timestamp(), " +
           "    version = version + 1 " +
           "WHERE id = :id " +
           "  AND status IN :eligibleStatuses " +
           "  AND version = :expectedVersion",
           nativeQuery = true)
    int claimExecutionForRollbackWithIntent(
            @Param("id") UUID id,
            @Param("eligibleStatuses") Collection<String> eligibleStatuses,
            @Param("expectedVersion") long expectedVersion,
            @Param("workerId") String workerId,
            @Param("targetPositions") String targetPositions,
            @Param("leaseDurationSeconds") long leaseDurationSeconds
    );

    /**
     * Atomically finalizes rollback completion with fencing verification, revoking worker lease ownership,
     * clearing active rollback intent, and recording completion timestamp (Phase 13G-D).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE migration_executions " +
           "SET status = :finalStatus, " +
           "    rollback_requested_at = NULL, " +
           "    rollback_target_positions = NULL, " +
           "    rollback_completed_at = clock_timestamp(), " +
           "    lease_owner = NULL, " +
           "    lease_until = NULL, " +
           "    completed_at = CASE WHEN :finalStatus = 'ROLLED_BACK' THEN clock_timestamp() ELSE completed_at END, " +
           "    updated_at = clock_timestamp(), " +
           "    version = version + 1 " +
           "WHERE id = :id " +
           "  AND status = 'RUNNING' " +
           "  AND lease_owner = :workerId " +
           "  AND lease_generation = :leaseGeneration " +
           "  AND lease_until IS NOT NULL " +
           "  AND lease_until > clock_timestamp()",
           nativeQuery = true)
    int finalizeRollbackWithFencing(
            @Param("id") UUID id,
            @Param("finalStatus") String finalStatus,
            @Param("workerId") String workerId,
            @Param("leaseGeneration") long leaseGeneration
    );
}
