package com.spotifyyoutube.migrator.migration.reliability.scheduling;

import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable value object representing a successfully claimed migration execution (Phase 13D-B, Phase 13G-A).
 * Returned by the atomic PostgreSQL claim transaction.
 *
 * @param executionId durable execution identifier
 * @param userId identifier of the user owning the execution
 * @param leaseGeneration the newly incremented fencing generation token
 * @param leaseUntil lease expiration timestamp according to database wall-clock time
 * @param workerId identifier of the claiming worker node/thread
 * @param preClaimStatus original parent execution status prior to the atomic claim transition
 */
public record ClaimedExecution(
        UUID executionId,
        UUID userId,
        long leaseGeneration,
        Instant leaseUntil,
        String workerId,
        MigrationExecutionStatus preClaimStatus
) {
    public ClaimedExecution {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        if (preClaimStatus == null) {
            preClaimStatus = MigrationExecutionStatus.QUEUED;
        }
    }

    public ClaimedExecution(
            UUID executionId,
            UUID userId,
            long leaseGeneration,
            Instant leaseUntil,
            String workerId
    ) {
        this(executionId, userId, leaseGeneration, leaseUntil, workerId, MigrationExecutionStatus.QUEUED);
    }
}
