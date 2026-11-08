package com.spotifyyoutube.migrator.migration.execution.repository;

import com.spotifyyoutube.migrator.migration.reliability.scheduling.ClaimedExecution;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Custom Spring Data JPA implementation providing lock-first, snapshot-safe lease release operations (Phase 13D-A),
 * atomic PostgreSQL scheduler claiming with FOR UPDATE SKIP LOCKED (Phase 13D-B), and executor saturation
 * compensation (Phase 13D-C).
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

    @Override
    @Transactional
    public Optional<ClaimedExecution> claimNextEligibleExecution(
            String workerId,
            long leaseDurationSeconds
    ) {
        if (workerId == null || workerId.isBlank() || leaseDurationSeconds <= 0) {
            return Optional.empty();
        }

        String sql =
                "WITH claimable AS (" +
                "    SELECT id " +
                "    FROM migration_executions " +
                "    WHERE (" +
                "        (status = 'QUEUED' AND (lease_until IS NULL OR lease_until < clock_timestamp())) " +
                "        OR " +
                "        (status = 'RUNNING' AND lease_until IS NOT NULL AND lease_until < clock_timestamp())" +
                "    ) " +
                "    AND (next_attempt_at IS NULL OR next_attempt_at <= clock_timestamp()) " +
                "    ORDER BY next_attempt_at ASC NULLS FIRST, created_at ASC " +
                "    LIMIT 1 " +
                "    FOR UPDATE SKIP LOCKED" +
                ") " +
                "UPDATE migration_executions m " +
                "SET status = 'RUNNING', " +
                "    lease_owner = :workerId, " +
                "    lease_generation = m.lease_generation + 1, " +
                "    lease_until = clock_timestamp() + (:leaseDurationSeconds * INTERVAL '1 second'), " +
                "    heartbeat_at = clock_timestamp(), " +
                "    next_attempt_at = NULL, " +
                "    updated_at = clock_timestamp(), " +
                "    version = m.version + 1 " +
                "FROM claimable " +
                "WHERE m.id = claimable.id " +
                "RETURNING m.id, m.user_id, m.lease_generation, m.lease_until, m.lease_owner";

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("workerId", workerId.trim());
        query.setParameter("leaseDurationSeconds", leaseDurationSeconds);

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        if (results.isEmpty()) {
            return Optional.empty();
        }

        Object[] row = results.get(0);
        UUID executionId = row[0] instanceof UUID u ? u : UUID.fromString(row[0].toString());
        UUID userId = row[1] instanceof UUID u ? u : UUID.fromString(row[1].toString());
        long generation = ((Number) row[2]).longValue();

        Instant leaseUntil;
        if (row[3] instanceof Timestamp ts) {
            leaseUntil = ts.toInstant();
        } else if (row[3] instanceof Instant inst) {
            leaseUntil = inst;
        } else if (row[3] instanceof OffsetDateTime odt) {
            leaseUntil = odt.toInstant();
        } else {
            leaseUntil = Instant.parse(row[3].toString());
        }

        String owner = (String) row[4];

        return Optional.of(new ClaimedExecution(executionId, userId, generation, leaseUntil, owner));
    }

    @Override
    @Transactional
    public int compensateClaimRejection(
            UUID id,
            String workerId,
            long leaseGeneration,
            Instant nextAttemptAt
    ) {
        if (id == null || workerId == null || workerId.isBlank()) {
            return 0;
        }

        String sql =
                "UPDATE migration_executions " +
                "SET status = 'QUEUED', " +
                "    lease_owner = NULL, " +
                "    lease_until = NULL, " +
                "    next_attempt_at = CAST(:nextAttemptAt AS TIMESTAMP WITH TIME ZONE), " +
                "    updated_at = clock_timestamp(), " +
                "    version = version + 1 " +
                "WHERE id = :id " +
                "  AND lease_owner = :workerId " +
                "  AND lease_generation = :leaseGeneration";

        return entityManager.createNativeQuery(sql)
                .setParameter("id", id)
                .setParameter("workerId", workerId.trim())
                .setParameter("leaseGeneration", leaseGeneration)
                .setParameter("nextAttemptAt", nextAttemptAt)
                .executeUpdate();
    }
}
