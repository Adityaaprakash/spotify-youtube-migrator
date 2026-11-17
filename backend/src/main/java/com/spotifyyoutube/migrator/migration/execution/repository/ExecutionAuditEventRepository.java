package com.spotifyyoutube.migrator.migration.execution.repository;

import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionAuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link ExecutionAuditEvent} (Phase 14D).
 */
@Repository
public interface ExecutionAuditEventRepository extends JpaRepository<ExecutionAuditEvent, UUID> {

    /**
     * Retrieves all audit events for a migration execution in chronological order.
     *
     * @param executionId migration execution identifier
     * @return chronological list of audit events
     */
    List<ExecutionAuditEvent> findByExecutionIdOrderByCreatedAtAsc(UUID executionId);

    /**
     * Retrieves a paginated timeline of audit events for a migration execution in chronological order.
     *
     * @param executionId migration execution identifier
     * @param pageable pagination parameters
     * @return paginated chronological audit events
     */
    Page<ExecutionAuditEvent> findByExecutionIdOrderByCreatedAtAsc(UUID executionId, Pageable pageable);

    /**
     * Counts audit events for a migration execution.
     *
     * @param executionId migration execution identifier
     * @return count of audit events
     */
    long countByExecutionId(UUID executionId);
}
