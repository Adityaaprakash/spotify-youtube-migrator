package com.spotifyyoutube.migrator.migration.execution.repository;

import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link ExecutionTrackRecord}.
 */
@Repository
public interface ExecutionTrackRecordRepository extends JpaRepository<ExecutionTrackRecord, UUID> {

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
}
