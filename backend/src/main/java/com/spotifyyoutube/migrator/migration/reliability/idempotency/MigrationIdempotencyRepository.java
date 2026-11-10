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
}
