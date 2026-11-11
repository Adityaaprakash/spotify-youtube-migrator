package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import java.util.Objects;
import java.util.UUID;

/**
 * Thread-local context carrying active idempotency claim ownership and fencing tokens
 * for the current HTTP request execution pipeline (Phase 13E).
 * <p>
 * Allows downstream orchestrator and service operations to verify they are still the legitimate,
 * unfenced owner before initiating external mutations (such as YouTube playlist creation)
 * or recording durable playlist identifiers.
 */
public record ClaimOwnershipContext(
        UUID claimId,
        UUID userId,
        String idempotencyKey,
        String claimOwner,
        long claimGeneration,
        IdempotencyClaimHeartbeatService heartbeatService
) {
    private static final ThreadLocal<ClaimOwnershipContext> CURRENT = new ThreadLocal<>();

    public ClaimOwnershipContext(
            UUID claimId,
            UUID userId,
            String idempotencyKey,
            String claimOwner,
            long claimGeneration
    ) {
        this(claimId, userId, idempotencyKey, claimOwner, claimGeneration, null);
    }

    public ClaimOwnershipContext {
        Objects.requireNonNull(claimId, "claimId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(claimOwner, "claimOwner must not be null");
    }

    /**
     * Checks whether the active claim ownership has been lost (e.g. expired lease, taken over,
     * or consecutive heartbeat renewal failures).
     *
     * @return true if claim was marked as ownership lost, false otherwise
     */
    public boolean isOwnershipLost() {
        if (heartbeatService != null && claimId != null) {
            return heartbeatService.isOwnershipLost(claimId);
        }
        return false;
    }

    /**
     * Sets the active claim ownership context for the current thread.
     *
     * @param context active ownership context
     */
    public static void set(ClaimOwnershipContext context) {
        CURRENT.set(context);
    }

    /**
     * Retrieves the active claim ownership context for the current thread, or {@code null} if none is bound.
     *
     * @return current context or null
     */
    public static ClaimOwnershipContext get() {
        return CURRENT.get();
    }

    /**
     * Clears the active claim ownership context from the current thread.
     */
    public static void clear() {
        CURRENT.remove();
    }
}
