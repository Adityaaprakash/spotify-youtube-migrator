package com.spotifyyoutube.migrator.migration.execution.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Custom Spring Data JPA implementation providing lock-first, validate-second fenced track claiming (Phase 13D-A).
 * <p>
 * Under PostgreSQL READ COMMITTED, multi-statement transaction execution ensures:
 * 1. The parent row lock is acquired first via SELECT ... FOR SHARE.
 * 2. Lease validity (lease_until > clock_timestamp()) is validated in a separate SQL statement executed
 *    strictly after the parent lock has been acquired and any lock wait has completed.
 * 3. The track record is conditionally updated while retaining the parent FOR SHARE lock until commit.
 */
public class ExecutionTrackRecordRepositoryImpl implements ExecutionTrackRecordRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public int claimTrackAddingWithFencing(UUID trackId, UUID executionId, long fencingGeneration) {
        if (trackId == null || executionId == null) {
            return 0;
        }

        // 1. Acquire parent row lock FOR SHARE using execution ID, owner presence, and fencing generation
        @SuppressWarnings("unchecked")
        List<Object> locked = entityManager.createNativeQuery(
                "SELECT id FROM migration_executions " +
                "WHERE id = :executionId " +
                "  AND lease_owner IS NOT NULL " +
                "  AND lease_generation = :fencingGeneration " +
                "FOR SHARE"
        )
        .setParameter("executionId", executionId)
        .setParameter("fencingGeneration", fencingGeneration)
        .getResultList();

        if (locked.isEmpty()) {
            return 0;
        }

        // 2. Separate SQL statement: validate lease validity (lease_until > clock_timestamp())
        // strictly AFTER parent lock is acquired and any lock wait has completed
        @SuppressWarnings("unchecked")
        List<Object> validLease = entityManager.createNativeQuery(
                "SELECT id FROM migration_executions " +
                "WHERE id = :executionId " +
                "  AND lease_until IS NOT NULL " +
                "  AND lease_until > clock_timestamp()"
        )
        .setParameter("executionId", executionId)
        .getResultList();

        if (validLease.isEmpty()) {
            return 0;
        }

        // 3. Separate SQL statement: conditionally mutate track record while retaining parent lock
        return entityManager.createNativeQuery(
                "UPDATE execution_track_records " +
                "SET status = 'ADDING', " +
                "    started_at = clock_timestamp(), " +
                "    attempt_count = attempt_count + 1, " +
                "    updated_at = clock_timestamp(), " +
                "    version = version + 1 " +
                "WHERE id = :trackId " +
                "  AND execution_id = :executionId " +
                "  AND status = 'PENDING'"
        )
        .setParameter("trackId", trackId)
        .setParameter("executionId", executionId)
        .executeUpdate();
    }

    @Override
    @Transactional
    public int claimTrackRollingBackWithFencing(UUID trackId, UUID executionId, long fencingGeneration) {
        if (trackId == null || executionId == null) {
            return 0;
        }

        // 1. Acquire parent row lock FOR SHARE using execution ID, owner presence, and fencing generation
        @SuppressWarnings("unchecked")
        List<Object> locked = entityManager.createNativeQuery(
                "SELECT id FROM migration_executions " +
                "WHERE id = :executionId " +
                "  AND lease_owner IS NOT NULL " +
                "  AND lease_generation = :fencingGeneration " +
                "FOR SHARE"
        )
        .setParameter("executionId", executionId)
        .setParameter("fencingGeneration", fencingGeneration)
        .getResultList();

        if (locked.isEmpty()) {
            return 0;
        }

        // 2. Separate SQL statement: validate lease validity (lease_until > clock_timestamp())
        // strictly AFTER parent lock is acquired and any lock wait has completed
        @SuppressWarnings("unchecked")
        List<Object> validLease = entityManager.createNativeQuery(
                "SELECT id FROM migration_executions " +
                "WHERE id = :executionId " +
                "  AND lease_until IS NOT NULL " +
                "  AND lease_until > clock_timestamp()"
        )
        .setParameter("executionId", executionId)
        .getResultList();

        if (validLease.isEmpty()) {
            return 0;
        }

        // 3. Separate SQL statement: conditionally mutate track record for rollback while retaining parent lock
        return entityManager.createNativeQuery(
                "UPDATE execution_track_records " +
                "SET status = 'ROLLING_BACK', " +
                "    started_at = clock_timestamp(), " +
                "    error_message = 'Compensating deletion in progress', " +
                "    updated_at = clock_timestamp(), " +
                "    version = version + 1 " +
                "WHERE id = :trackId " +
                "  AND execution_id = :executionId " +
                "  AND status = 'ADDED'"
        )
        .setParameter("trackId", trackId)
        .setParameter("executionId", executionId)
        .executeUpdate();
    }
}
