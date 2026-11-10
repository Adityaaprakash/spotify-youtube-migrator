package com.spotifyyoutube.migrator.migration.reliability.idempotency;

/**
 * Lifecycle status of an API idempotency reservation record (Phase 13E).
 */
public enum IdempotencyClaimStatus {
    /**
     * Claim is actively held by a winning thread preparing or dispatching execution.
     * Prevents duplicate execution creation or external side effects by concurrent callers.
     */
    PENDING,

    /**
     * Submission has successfully completed and execution has been durably initialized.
     * Subsequent matching requests will replay the cached response.
     */
    COMPLETED
}
