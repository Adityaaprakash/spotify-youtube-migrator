package com.spotifyyoutube.migrator.migration.execution.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Custom Spring Data JPA implementation providing lock-first, snapshot-safe lease release operations (Phase 13D-A).
 * <p>
 * Under PostgreSQL READ COMMITTED, multi-statement transaction execution ensures:
 * 1. The parent row lock is acquired first via SELECT ... FOR UPDATE.
 * 2. Active in-flight tracks are counted via a separate SQL query using a fresh statement snapshot established
 *    after the parent row lock is acquired.
 * 3. The lease is conditionally cleared only if zero active tracks exist in ADDING or ROLLING_BACK.
 */
public class MigrationExecutionRepositoryImpl implements MigrationExecutionRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public int releaseLease(
            UUID id,
            String workerId,
            long leaseGeneration,
            String newStatus,
            Instant nextAttemptAt
    ) {
        if (id == null || workerId == null || workerId.isBlank()) {
            return 0;
        }

        // 1. Acquire parent row lock FOR UPDATE using execution ID, worker ID, and fencing generation
        @SuppressWarnings("unchecked")
        List<Object> locked = entityManager.createNativeQuery(
                "SELECT id FROM migration_executions " +
                "WHERE id = :id " +
                "  AND lease_owner = :workerId " +
                "  AND lease_generation = :leaseGeneration " +
                "FOR UPDATE"
        )
        .setParameter("id", id)
        .setParameter("workerId", workerId.trim())
        .setParameter("leaseGeneration", leaseGeneration)
        .getResultList();

        if (locked.isEmpty()) {
            return 0;
        }

        // 2. Separate SQL statement: validate lease validity (lease_until > clock_timestamp())
        // strictly AFTER parent lock is acquired and any lock wait has completed
        @SuppressWarnings("unchecked")
        List<Object> validLease = entityManager.createNativeQuery(
                "SELECT id FROM migration_executions " +
                "WHERE id = :id " +
                "  AND lease_until IS NOT NULL " +
                "  AND lease_until > clock_timestamp()"
        )
        .setParameter("id", id)
        .getResultList();

        if (validLease.isEmpty()) {
            return 0;
        }

        // 3. Separate SQL statement: count active in-flight tracks under fresh READ COMMITTED snapshot
        Number activeCount = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM execution_track_records " +
                "WHERE execution_id = :id " +
                "  AND status IN ('ADDING', 'ROLLING_BACK')"
        )
        .setParameter("id", id)
        .getSingleResult();

        if (activeCount != null && activeCount.longValue() > 0) {
            return 0;
        }

        // 4. Separate SQL statement: conditionally clear lease ownership and set execution status
        return entityManager.createNativeQuery(
                "UPDATE migration_executions " +
                "SET status = COALESCE(CAST(:newStatus AS VARCHAR), status), " +
                "    lease_owner = NULL, " +
                "    lease_until = NULL, " +
                "    next_attempt_at = CAST(:nextAttemptAt AS TIMESTAMP WITH TIME ZONE), " +
                "    updated_at = clock_timestamp() " +
                "WHERE id = :id " +
                "  AND lease_owner = :workerId " +
                "  AND lease_generation = :leaseGeneration"
        )
        .setParameter("newStatus", newStatus)
        .setParameter("nextAttemptAt", nextAttemptAt)
        .setParameter("id", id)
        .setParameter("workerId", workerId.trim())
        .setParameter("leaseGeneration", leaseGeneration)
        .executeUpdate();
    }
}
