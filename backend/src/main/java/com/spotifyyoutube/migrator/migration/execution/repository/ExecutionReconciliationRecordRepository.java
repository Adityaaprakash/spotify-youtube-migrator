package com.spotifyyoutube.migrator.migration.execution.repository;

import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionReconciliationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link ExecutionReconciliationRecord} (Phase 11G).
 */
@Repository
public interface ExecutionReconciliationRecordRepository extends JpaRepository<ExecutionReconciliationRecord, UUID> {

    /**
     * Retrieves all reconciliation audit records for the given migration execution ordered by creation timestamp ascending.
     *
     * @param executionId execution identifier
     * @return list of reconciliation audit records
     */
    List<ExecutionReconciliationRecord> findByExecutionIdOrderByCreatedAtAsc(UUID executionId);

    /**
     * Retrieves all reconciliation attempts for an individual track record.
     *
     * @param trackRecordId track record identifier
     * @return list of reconciliation records
     */
    List<ExecutionReconciliationRecord> findByTrackRecordIdOrderByCreatedAtAsc(UUID trackRecordId);
}
