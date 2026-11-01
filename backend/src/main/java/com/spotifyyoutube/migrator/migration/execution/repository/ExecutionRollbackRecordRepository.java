package com.spotifyyoutube.migrator.migration.execution.repository;

import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionRollbackRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link ExecutionRollbackRecord} (Phase 11F).
 */
@Repository
public interface ExecutionRollbackRecordRepository extends JpaRepository<ExecutionRollbackRecord, UUID> {

    /**
     * Retrieves all rollback audit records for the given migration execution ordered by target position.
     *
     * @param executionId execution identifier
     * @return list of rollback records in ascending target position order
     */
    List<ExecutionRollbackRecord> findByExecutionIdOrderByTargetPositionAsc(UUID executionId);

    /**
     * Retrieves all rollback attempts for an individual track record.
     *
     * @param trackRecordId track record identifier
     * @return list of rollback records
     */
    List<ExecutionRollbackRecord> findByTrackRecordId(UUID trackRecordId);
}
