package com.spotifyyoutube.migrator.migration.execution.repository;

import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
