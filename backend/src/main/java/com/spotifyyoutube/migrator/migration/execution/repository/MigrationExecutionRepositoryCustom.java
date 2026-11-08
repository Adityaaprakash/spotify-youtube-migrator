package com.spotifyyoutube.migrator.migration.execution.repository;

import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.reliability.scheduling.ClaimedExecution;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Custom extension interface for {@link MigrationExecutionRepository} providing lock-first,
 * snapshot-safe lease release operations (Phase 13D-A), atomic scheduler claiming (Phase 13D-B),
 * and executor saturation compensation (Phase 13D-C).
 */
public interface MigrationExecutionRepositoryCustom {

    /**
     * Conditionally releases worker lease ownership using a lock-first explicit transaction sequence (Phase 13D-A).
     *
     * @param id execution ID
     * @param workerId worker identity
     * @param leaseGeneration expected fencing token
     * @param newStatus status to set (e.g. QUEUED, COMPLETED, FAILED)
     * @param nextAttemptAt optional next attempt timestamp
     * @return 1 if released, 0 if lease ownership is stale, expired, or active tracks are in-flight
     */
    int releaseLease(
            UUID id,
            String workerId,
            long leaseGeneration,
            String newStatus,
            Instant nextAttemptAt
    );

    default int releaseLease(
            UUID id,
            String workerId,
            long leaseGeneration,
            MigrationExecutionStatus newStatus,
            Instant nextAttemptAt
    ) {
        return releaseLease(id, workerId, leaseGeneration, newStatus != null ? newStatus.name() : null, nextAttemptAt);
    }

    /**
     * Atomically claims the next eligible execution for processing using PostgreSQL row-level locking
     * and FOR UPDATE SKIP LOCKED within a short database transaction (Phase 13D-B).
     *
     * @param workerId worker node/instance identity
     * @param leaseDurationSeconds lease validity duration in seconds
     * @return Optional containing the claimed execution details, or empty if no eligible execution is available
     */
    Optional<ClaimedExecution> claimNextEligibleExecution(
            String workerId,
            long leaseDurationSeconds
    );

    default Optional<ClaimedExecution> claimNextEligibleExecution(String workerId) {
        return claimNextEligibleExecution(workerId, 60L);
    }

    /**
     * Safely compensates an executor dispatch rejection (e.g. saturation or shutdown) by releasing
     * the worker lease and resetting the execution to QUEUED with a deferred retry backoff (Phase 13D-C).
     * Strictly conditional on execution ID, worker ID, and exact lease generation to prevent overwriting
     * newer worker claims.
     *
     * @param id execution ID
     * @param workerId original claiming worker identity
     * @param leaseGeneration original acquired fencing generation
     * @param nextAttemptAt earliest timestamp when this execution becomes eligible again
     * @return 1 if compensated successfully, 0 if ownership or generation no longer matches
     */
    int compensateClaimRejection(
            UUID id,
            String workerId,
            long leaseGeneration,
            Instant nextAttemptAt
    );
}

