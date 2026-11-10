package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for durable {@link MigrationIdempotencyRecord} persistence (Phase 13E).
 * <p>
 * Implements database-atomic claim acquisition, monotonic claim fencing, conditional ownership verification,
 * lease heartbeat renewal, and terminal-state lifecycle retention using PostgreSQL wall-clock time.
 */
@Repository
public interface MigrationIdempotencyRepository extends JpaRepository<MigrationIdempotencyRecord, UUID> {

    /**
     * Looks up an idempotency record by authenticated user ID and client idempotency key.
     *
     * @param userId         authenticated user identifier
     * @param idempotencyKey client-provided idempotency key
     * @return optional containing the persisted record if present
     */
    Optional<MigrationIdempotencyRecord> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    /**
     * Finds all idempotency records associated with a specific migration execution ID.
     *
     * @param executionId execution aggregate identifier
     * @return list of associated idempotency records
     */
    List<MigrationIdempotencyRecord> findByExecutionId(UUID executionId);

    /**
     * Finds all pending claims associated with a given review session ID.
     *
     * @param reviewSessionId review session identifier
     * @param status          claim status (typically PENDING)
     * @return list of matching records
     */
    List<MigrationIdempotencyRecord> findByReviewSessionIdAndStatus(UUID reviewSessionId, IdempotencyClaimStatus status);

    /**
     * Updates the expiration timestamp for all idempotency records associated with an execution
     * that do not already have an expiry set.
     *
     * @param executionId execution aggregate identifier
     * @param expiresAt   calculated expiry timestamp (terminal transition time + 72 hours)
     * @return number of records updated
     */
    @Modifying
    @Query("UPDATE MigrationIdempotencyRecord r SET r.expiresAt = :expiresAt "
            + "WHERE r.executionId = :executionId AND r.expiresAt IS NULL")
    int setTerminalExpiry(
            @Param("executionId") UUID executionId,
            @Param("expiresAt") Instant expiresAt
    );

    /**
     * Deletes expired idempotency records whose {@code expires_at} timestamp is strictly before the given cutoff.
     * Preserves all active and recoverable records where {@code expires_at IS NULL}.
     *
     * @param cutoff timestamp threshold (records expiring before this are deleted)
     * @return count of deleted records
     */
    @Modifying
    @Query("DELETE FROM MigrationIdempotencyRecord r "
            + "WHERE r.expiresAt IS NOT NULL AND r.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

    /**
     * Bounded cleanup sweeper using authoritative database clock time.
     *
     * @param limit maximum number of rows to delete per cycle
     * @return number of deleted records
     */
    @Modifying
    @Query(value = """
            WITH to_delete AS (
                SELECT id FROM migration_idempotency_keys
                WHERE expires_at IS NOT NULL AND expires_at < clock_timestamp()
                LIMIT :limit
            )
            DELETE FROM migration_idempotency_keys m
            USING to_delete
            WHERE m.id = to_delete.id
            """, nativeQuery = true)
    int deleteExpiredDatabaseTime(@Param("limit") int limit);

    /**
     * Atomically takes over an expired PENDING claim by resetting claim_expires_at to a new future timestamp.
     *
     * @param id                 claim record ID
     * @param newClaimExpiresAt  new expiration timestamp for the claim
     * @param now                current time cutoff
     * @return number of updated rows (1 if successful takeover, 0 otherwise)
     */
    @Modifying
    @Query("UPDATE MigrationIdempotencyRecord r SET r.claimExpiresAt = :newClaimExpiresAt "
            + "WHERE r.id = :id AND r.status = com.spotifyyoutube.migrator.migration.reliability.idempotency.IdempotencyClaimStatus.PENDING "
            + "AND r.claimExpiresAt <= :now")
    int takeOverClaim(
            @Param("id") UUID id,
            @Param("newClaimExpiresAt") Instant newClaimExpiresAt,
            @Param("now") Instant now
    );

    /**
     * Atomically takes over an expired PENDING claim with monotonic fencing token increment (Phase 13E).
     * Enforces that takeover increments {@code claim_generation = claim_generation + 1}, assigns a new
     * {@code claim_owner}, and establishes a new lease using PostgreSQL {@code clock_timestamp()}.
     *
     * @param id                 claim record ID
     * @param newClaimOwner      new leader ownership identity
     * @param expectedGeneration current generation to fence against
     * @param timeoutSeconds     lease duration in seconds
     * @return 1 if successfully taken over, 0 if lease already renewed, completed, or taken over
     */
    @Modifying
    @Query(value = """
            UPDATE migration_idempotency_keys
            SET claim_owner = :newClaimOwner,
                claim_generation = claim_generation + 1,
                claim_expires_at = clock_timestamp() + (:timeoutSeconds * INTERVAL '1 second'),
                heartbeat_at = clock_timestamp()
            WHERE id = :id
              AND status = 'PENDING'
              AND claim_generation = :expectedGeneration
              AND claim_expires_at <= clock_timestamp()
            """, nativeQuery = true)
    int takeOverClaimWithFencingDatabaseTime(
            @Param("id") UUID id,
            @Param("newClaimOwner") String newClaimOwner,
            @Param("expectedGeneration") long expectedGeneration,
            @Param("timeoutSeconds") long timeoutSeconds
    );

    /**
     * Conditionally renews an active PENDING claim lease using PostgreSQL database wall-clock time (Phase 13E).
     * Requires exact match on {@code claim_owner} and {@code claim_generation}, and verifies that the lease
     * has not already expired or been taken over.
     *
     * @param id               claim record ID
     * @param claimOwner       expected leader ownership identity
     * @param claimGeneration  expected leader fencing generation
     * @param extensionSeconds additional lease duration in seconds
     * @return 1 if successfully renewed, 0 if ownership lost or lease expired
     */
    @Modifying
    @Query(value = """
            UPDATE migration_idempotency_keys
            SET claim_expires_at = clock_timestamp() + (:extensionSeconds * INTERVAL '1 second'),
                heartbeat_at = clock_timestamp()
            WHERE id = :id
              AND claim_owner = :claimOwner
              AND claim_generation = :claimGeneration
              AND status = 'PENDING'
              AND claim_expires_at > clock_timestamp()
            """, nativeQuery = true)
    int renewClaimDatabaseTime(
            @Param("id") UUID id,
            @Param("claimOwner") String claimOwner,
            @Param("claimGeneration") long claimGeneration,
            @Param("extensionSeconds") long extensionSeconds
    );

    /**
     * Completes a PENDING claim with execution identity and response payload.
     *
     * @param id              claim record ID
     * @param executionId     persisted execution aggregate identifier
     * @param responseStatus  HTTP response status
     * @param responsePayload serialized response payload
     * @param locationHeader  Location header URL
     * @return number of updated rows (1 if successfully completed)
     */
    @Modifying
    @Query("UPDATE MigrationIdempotencyRecord r SET "
            + "r.status = com.spotifyyoutube.migrator.migration.reliability.idempotency.IdempotencyClaimStatus.COMPLETED, "
            + "r.executionId = :executionId, r.responseStatus = :responseStatus, r.responsePayload = :responsePayload, "
            + "r.locationHeader = :locationHeader, r.claimExpiresAt = NULL "
            + "WHERE r.id = :id AND r.status = com.spotifyyoutube.migrator.migration.reliability.idempotency.IdempotencyClaimStatus.PENDING")
    int completeClaim(
            @Param("id") UUID id,
            @Param("executionId") UUID executionId,
            @Param("responseStatus") int responseStatus,
            @Param("responsePayload") String responsePayload,
            @Param("locationHeader") String locationHeader
    );

    /**
     * Completes a PENDING claim protected by strict ownership and fencing verification (Phase 13E).
     * Prevents stale leaders from completing claims that were previously taken over by a newer leader.
     *
     * @param id              claim record ID
     * @param claimOwner      expected leader ownership identity
     * @param claimGeneration expected leader fencing generation
     * @param executionId     persisted execution aggregate identifier
     * @param responseStatus  HTTP response status
     * @param responsePayload serialized response payload
     * @param locationHeader  Location header URL
     * @return number of updated rows (1 if successfully completed, 0 if stale)
     */
    @Modifying
    @Query("UPDATE MigrationIdempotencyRecord r SET "
            + "r.status = com.spotifyyoutube.migrator.migration.reliability.idempotency.IdempotencyClaimStatus.COMPLETED, "
            + "r.executionId = :executionId, r.responseStatus = :responseStatus, r.responsePayload = :responsePayload, "
            + "r.locationHeader = :locationHeader, r.claimExpiresAt = NULL "
            + "WHERE r.id = :id AND r.status = com.spotifyyoutube.migrator.migration.reliability.idempotency.IdempotencyClaimStatus.PENDING "
            + "AND r.claimOwner = :claimOwner AND r.claimGeneration = :claimGeneration")
    int completeClaimWithFencing(
            @Param("id") UUID id,
            @Param("claimOwner") String claimOwner,
            @Param("claimGeneration") long claimGeneration,
            @Param("executionId") UUID executionId,
            @Param("responseStatus") int responseStatus,
            @Param("responsePayload") String responsePayload,
            @Param("locationHeader") String locationHeader
    );

    /**
     * Deletes a PENDING claim that failed before execution creation so the key can be retried immediately.
     *
     * @param userId         authenticated user ID
     * @param idempotencyKey client idempotency key
     * @param status         must be PENDING
     * @return number of deleted rows
     */
    @Modifying
    @Query("DELETE FROM MigrationIdempotencyRecord r WHERE r.userId = :userId "
            + "AND r.idempotencyKey = :idempotencyKey AND r.status = :status AND r.executionId IS NULL")
    int deletePendingClaim(
            @Param("userId") UUID userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("status") IdempotencyClaimStatus status
    );

    /**
     * Deletes a PENDING claim protected by strict ownership and fencing verification (Phase 13E).
     * Ensures that if another leader took over the claim, a stale leader's failure rollback
     * does not delete the new leader's claim.
     *
     * @param userId          authenticated user ID
     * @param idempotencyKey  client idempotency key
     * @param status          must be PENDING
     * @param claimOwner      expected leader ownership identity
     * @param claimGeneration expected leader fencing generation
     * @return number of deleted rows (1 if successfully deleted, 0 if stale)
     */
    @Modifying
    @Query("DELETE FROM MigrationIdempotencyRecord r WHERE r.userId = :userId "
            + "AND r.idempotencyKey = :idempotencyKey AND r.status = :status "
            + "AND r.claimOwner = :claimOwner AND r.claimGeneration = :claimGeneration "
            + "AND r.executionId IS NULL")
    int deletePendingClaimWithFencing(
            @Param("userId") UUID userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("status") IdempotencyClaimStatus status,
            @Param("claimOwner") String claimOwner,
            @Param("claimGeneration") long claimGeneration
    );

    /**
     * Marks the claim stage as EXTERNAL_OPERATION_STARTED for any active pending claim on the review session.
     *
     * @param reviewSessionId review session identifier
     * @return number of records updated
     */
    @Modifying
    @Query(value = """
            UPDATE migration_idempotency_keys
            SET claim_stage = 'EXTERNAL_OPERATION_STARTED'
            WHERE review_session_id = :reviewSessionId
              AND status = 'PENDING'
              AND claim_stage = 'ACQUIRED'
            """, nativeQuery = true)
    int markExternalOperationStartedForSession(@Param("reviewSessionId") UUID reviewSessionId);

    /**
     * Records the created destination playlist ID on any active pending claim on the review session.
     *
     * @param reviewSessionId       review session identifier
     * @param destinationPlaylistId YouTube playlist ID
     * @return number of records updated
     */
    @Modifying
    @Query(value = """
            UPDATE migration_idempotency_keys
            SET destination_playlist_id = :destinationPlaylistId,
                claim_stage = 'PLAYLIST_CREATED'
            WHERE review_session_id = :reviewSessionId
              AND status = 'PENDING'
            """, nativeQuery = true)
    int recordDestinationPlaylistForSession(
            @Param("reviewSessionId") UUID reviewSessionId,
            @Param("destinationPlaylistId") String destinationPlaylistId
    );
}
