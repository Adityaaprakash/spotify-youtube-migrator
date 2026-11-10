package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;

/**
 * Thrown when an idempotency claim owner attempts an operation (completion, renewal, release)
 * with an outdated claim owner token or mismatched fencing generation (Phase 13E).
 * <p>
 * Signals that another leader thread or node has taken over the expired claim,
 * preventing split-brain execution and ensuring a stale leader never completes a newer leader's claim.
 */
public class StaleClaimOwnershipException extends InvalidStateException {

    private final String idempotencyKey;
    private final String attemptedOwner;
    private final long attemptedGeneration;

    public StaleClaimOwnershipException(String idempotencyKey, String attemptedOwner, long attemptedGeneration) {
        super(String.format("Idempotency claim ownership for key '%s' has expired or was taken over "
                + "(attempted owner: %s, generation: %d)", idempotencyKey, attemptedOwner, attemptedGeneration));
        this.idempotencyKey = idempotencyKey;
        this.attemptedOwner = attemptedOwner;
        this.attemptedGeneration = attemptedGeneration;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getAttemptedOwner() {
        return attemptedOwner;
    }

    public long getAttemptedGeneration() {
        return attemptedGeneration;
    }
}
