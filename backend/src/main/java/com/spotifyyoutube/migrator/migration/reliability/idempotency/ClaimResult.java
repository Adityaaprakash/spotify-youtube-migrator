package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of an atomic idempotency claim acquisition attempt (Phase 13E).
 * <p>
 * Carries fencing metadata (claim owner token and monotonically increasing generation number)
 * to protect against stale leader execution and split-brain states.
 */
public record ClaimResult(
        ClaimResultType type,
        MigrationIdempotencyRecord record,
        String message,
        String claimOwner,
        long claimGeneration
) {
    public enum ClaimResultType {
        /**
         * Caller successfully acquired the leader claim under a specific ownership identity and generation.
         * Must execute downstream and conditionally complete the claim.
         */
        ACQUIRED,

        /**
         * A matching completed record was found. Caller should replay the cached response.
         */
        REPLAY,

        /**
         * A matching claim is currently pending in another concurrent thread. Caller must poll.
         */
        PENDING,

        /**
         * The idempotency key was previously claimed with a different request fingerprint. Conflict (409).
         */
        MISMATCH,

        /**
         * The claim expired after an external mutation (e.g. YouTube playlist creation) may have completed
         * but before execution persistence. Takeover is aborted and caller fails closed into reconciliation.
         */
        RECONCILIATION_REQUIRED
    }

    public static ClaimResult acquired(MigrationIdempotencyRecord record) {
        String owner = record != null ? record.getClaimOwner() : null;
        long gen = record != null ? record.getClaimGeneration() : 1L;
        return new ClaimResult(ClaimResultType.ACQUIRED, Objects.requireNonNull(record), null, owner, gen);
    }

    public static ClaimResult acquired(MigrationIdempotencyRecord record, String claimOwner, long claimGeneration) {
        return new ClaimResult(ClaimResultType.ACQUIRED, Objects.requireNonNull(record), null, claimOwner, claimGeneration);
    }

    public static ClaimResult replay(MigrationIdempotencyRecord record) {
        return new ClaimResult(ClaimResultType.REPLAY, Objects.requireNonNull(record), null, null, 0L);
    }

    public static ClaimResult pending(MigrationIdempotencyRecord record) {
        return new ClaimResult(ClaimResultType.PENDING, Objects.requireNonNull(record), null, null, 0L);
    }

    public static ClaimResult mismatch(String message) {
        return new ClaimResult(ClaimResultType.MISMATCH, null, message, null, 0L);
    }

    public static ClaimResult reconciliationRequired(String message, MigrationIdempotencyRecord record) {
        return new ClaimResult(ClaimResultType.RECONCILIATION_REQUIRED, record, message, null, 0L);
    }

    public boolean isAcquired() {
        return type == ClaimResultType.ACQUIRED;
    }

    public boolean isReplay() {
        return type == ClaimResultType.REPLAY;
    }

    public boolean isPending() {
        return type == ClaimResultType.PENDING;
    }

    public boolean isMismatch() {
        return type == ClaimResultType.MISMATCH;
    }

    public boolean isReconciliationRequired() {
        return type == ClaimResultType.RECONCILIATION_REQUIRED;
    }

    public Optional<MigrationIdempotencyRecord> getRecord() {
        return Optional.ofNullable(record);
    }
}
