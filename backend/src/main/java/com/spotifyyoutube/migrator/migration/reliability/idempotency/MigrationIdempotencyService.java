package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service coordinating API idempotency records, atomic claim reservations,
 * monotonic claim fencing, lease renewals, replay detection, crash recovery, and safe cleanup (Phase 13E).
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
     * Atomically acquires an idempotency claim before execution preparation or dispatch.
     *
     * @param userId             authenticated user identifier
     * @param idempotencyKey     client key
     * @param requestFingerprint canonical request fingerprint
     * @param reviewSessionId    optional review session ID for crash reconciliation
     * @return claim result with fencing tokens
     */
    ClaimResult acquireClaim(
            UUID userId,
            String idempotencyKey,
            String requestFingerprint,
            UUID reviewSessionId
    );

    /**
     * Completes a previously acquired PENDING claim by associating it with a persisted execution.
     *
     * @param userId             authenticated user identifier
     * @param idempotencyKey     client key
     * @param executionId        persisted execution ID
     * @param responseStatus     HTTP status code
     * @param responsePayload    serialized response payload
     * @param locationHeader     Location header URL
     * @return completed idempotency record
     */
    MigrationIdempotencyRecord completeClaim(
            UUID userId,
            String idempotencyKey,
            UUID executionId,
            int responseStatus,
            String responsePayload,
            String locationHeader
    );

    /**
     * Completes a PENDING claim protected by strict ownership and fencing verification (Phase 13E).
     *
     * @param userId             authenticated user identifier
     * @param idempotencyKey     client key
     * @param claimOwner         expected claim owner token
     * @param claimGeneration    expected fencing generation
     * @param executionId        persisted execution ID
     * @param responseStatus     HTTP status code
     * @param responsePayload    serialized response payload
     * @param locationHeader     Location header URL
     * @return completed idempotency record
     * @throws StaleClaimOwnershipException if ownership was lost or taken over
     */
    MigrationIdempotencyRecord completeClaim(
            UUID userId,
            String idempotencyKey,
            String claimOwner,
            long claimGeneration,
            UUID executionId,
            int responseStatus,
            String responsePayload,
            String locationHeader
    );

    /**
     * Releases a PENDING claim if downstream execution preparation failed before creating an execution.
     *
     * @param userId         authenticated user identifier
     * @param idempotencyKey client key
     */
    void releaseClaim(UUID userId, String idempotencyKey);

    /**
     * Releases a PENDING claim protected by strict ownership and fencing verification (Phase 13E).
     *
     * @param userId          authenticated user identifier
     * @param idempotencyKey  client key
     * @param claimOwner      expected claim owner token
     * @param claimGeneration expected fencing generation
     */
    void releaseClaim(UUID userId, String idempotencyKey, String claimOwner, long claimGeneration);

    /**
     * Renews an active PENDING claim lease using PostgreSQL database wall-clock time (Phase 13E).
     *
     * @param userId          authenticated user identifier
     * @param idempotencyKey  client key
     * @param claimOwner      expected claim owner token
     * @param claimGeneration expected fencing generation
     * @param extension       lease duration extension
     * @return true if successfully renewed, false if ownership was lost or expired
     */
    boolean renewClaim(
            UUID userId,
            String idempotencyKey,
            String claimOwner,
            long claimGeneration,
            Duration extension
    );

    /**
     * Signals that an external mutation (e.g. YouTube playlist creation) has commenced for the session.
     *
     * @param reviewSessionId review session identifier
     * @return number of updated pending claims
     */
    int markExternalOperationStartedForSession(UUID reviewSessionId);

    /**
     * Signals that an external mutation has commenced, recording the stable operation UUID (Phase 13F).
     *
     * @param reviewSessionId review session identifier
     * @param operationId stable playlist creation operation UUID
     * @return number of updated pending claims
     */
    int markExternalOperationStartedForSession(UUID reviewSessionId, UUID operationId);

    /**
     * Persists the allocated destination playlist ID to the pending claim record before execution creation.
     *
     * @param reviewSessionId       review session identifier
     * @param destinationPlaylistId remote playlist ID
     * @return number of updated pending claims
     */
    int recordDestinationPlaylistForSession(UUID reviewSessionId, String destinationPlaylistId);

    /**
     * Atomically records a migration submission outcome associated with an idempotency key.
     *
     * @param userId             authenticated user identifier
     * @param idempotencyKey     validated client key
     * @param requestFingerprint canonical request fingerprint
     * @param executionId        persisted execution identifier
     * @param responseStatus     HTTP status code of the submission response
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
     *
     * @param executionId            execution aggregate identifier
     * @param terminalTransitionTime timestamp when terminal state was reached
     * @return count of updated records
     */
    int markExecutionTerminal(UUID executionId, Instant terminalTransitionTime);

    /**
     * Performs scheduled cleanup of expired terminal idempotency records using database clock time.
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
