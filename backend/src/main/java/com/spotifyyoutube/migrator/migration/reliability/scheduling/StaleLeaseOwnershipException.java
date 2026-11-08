package com.spotifyyoutube.migrator.migration.reliability.scheduling;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;

import java.util.UUID;

/**
 * Thrown when a worker attempts a lease renewal, state mutation, or lease release holding an
 * outdated or mismatched fencing generation token (Phase 13D-A).
 * <p>
 * Immunizes the distributed worker pool against split-brain mutations from zombie workers
 * that resume writing after a pause, partition, or lease expiration.
 */
public class StaleLeaseOwnershipException extends InvalidStateException {

    private final UUID executionId;
    private final String workerId;
    private final long attemptedGeneration;
    private final Long currentGeneration;

    public StaleLeaseOwnershipException(
            UUID executionId,
            String workerId,
            long attemptedGeneration,
            Long currentGeneration,
            String message
    ) {
        super(message);
        this.executionId = executionId;
        this.workerId = workerId;
        this.attemptedGeneration = attemptedGeneration;
        this.currentGeneration = currentGeneration;
    }

    public StaleLeaseOwnershipException(String message) {
        super(message);
        this.executionId = null;
        this.workerId = null;
        this.attemptedGeneration = 0L;
        this.currentGeneration = null;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public long getAttemptedGeneration() {
        return attemptedGeneration;
    }

    public Long getCurrentGeneration() {
        return currentGeneration;
    }
}
