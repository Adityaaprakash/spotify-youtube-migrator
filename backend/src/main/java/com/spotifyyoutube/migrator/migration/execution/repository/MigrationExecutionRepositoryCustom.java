package com.spotifyyoutube.migrator.migration.execution.repository;

import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Custom extension interface for {@link MigrationExecutionRepository} providing lock-first,
 * snapshot-safe lease release operations (Phase 13D-A).
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
}
