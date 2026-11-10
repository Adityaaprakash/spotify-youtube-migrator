package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service coordinating API idempotency records, replay detection,
 * terminal-state lifecycle retention, and safe cleanup (Phase 13E).
 */
public interface MigrationIdempotencyService {

    /**
     * Looks up an existing idempotency record for the given authenticated user and key.
     *
     * @param userId         authenticated user identifier
     * @param idempotencyKey client-provided key
     * @return optional containing the record if present
     */
    Optional<MigrationIdempotencyRecord> findExistingRecord(UUID userId, String idempotencyKey);

    /**
     * Atomically records a migration submission outcome associated with an idempotency key.
     * <p>
     * If a concurrent duplicate submission previously committed for the same {@code (userId, idempotencyKey)}:
     * <ul>
     *   <li>If the request fingerprint matches: returns the existing record for replay</li>
     *   <li>If the request fingerprint differs: throws {@link IdempotencyFingerprintMismatchException} (HTTP 409)</li>
     * </ul>
     *
     * @param userId             authenticated user identifier
     * @param idempotencyKey     validated client key
     * @param requestFingerprint canonical request fingerprint
     * @param executionId        persisted execution identifier
     * @param responseStatus     HTTP status code of the submission response (e.g. 200, 202)
     * @param responsePayload    serialized response body
     * @return persisted or existing idempotency record
     */
    MigrationIdempotencyRecord recordSubmission(
            UUID userId,
            String idempotencyKey,
            String requestFingerprint,
            UUID executionId,
            int responseStatus,
            String responsePayload
    );

    /**
     * Updates retention for all idempotency records associated with the specified execution ID
     * upon transition to a terminal state (COMPLETED, FAILED, ROLLED_BACK).
     * Sets {@code expires_at = terminalTransitionTime + 72 hours}.
     * Idempotent: repeated transitions will not extend the retention period.
     *
     * @param executionId            execution aggregate identifier
     * @param terminalTransitionTime timestamp when terminal state was reached
     * @return count of updated records
     */
    int markExecutionTerminal(UUID executionId, Instant terminalTransitionTime);

    /**
     * Performs scheduled cleanup of expired terminal idempotency records using database clock time.
     * Preserves all active and recoverable records where {@code expires_at IS NULL}.
     *
     * @return count of deleted records
     */
    int cleanupExpiredRecords();

    /**
     * Performs cleanup of expired records against an explicit cutoff timestamp.
     *
     * @param cutoff cutoff timestamp
     * @return count of deleted records
     */
    int cleanupExpiredRecords(Instant cutoff);
}
