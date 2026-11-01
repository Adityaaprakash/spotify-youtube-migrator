package com.spotifyyoutube.migrator.migration.execution.repository;

import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
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
 * Spring Data JPA repository for {@link MigrationExecution}.
 */
@Repository
public interface MigrationExecutionRepository extends JpaRepository<MigrationExecution, UUID> {

    Optional<MigrationExecution> findByReviewSessionId(UUID reviewSessionId);

    Optional<MigrationExecution> findByIdAndUserId(UUID id, UUID userId);

    List<MigrationExecution> findByUserIdOrderByCreatedAtDesc(UUID userId);

    boolean existsByReviewSessionId(UUID reviewSessionId);

    /**
     * Atomically claims execution for processing by updating status to RUNNING if not already RUNNING.
     * Guaranteed cross-instance atomic claiming backed by PostgreSQL row-level locking.
     *
     * @param id execution ID
     * @param now current timestamp
     * @return 1 if successfully claimed, 0 if already RUNNING or not found
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MigrationExecution m SET m.status = com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus.RUNNING, " +
           "m.updatedAt = :now, m.version = m.version + 1 " +
           "WHERE m.id = :id AND m.status != com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus.RUNNING")
    int claimExecutionRunning(@Param("id") UUID id, @Param("now") Instant now);
}
