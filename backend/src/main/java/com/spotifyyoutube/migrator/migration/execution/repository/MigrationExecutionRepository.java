package com.spotifyyoutube.migrator.migration.execution.repository;

import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
