package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Persisted idempotency record binding an authenticated user and client key to an execution response (Phase 13E).
 * <p>
 * Enforces:
 * <ul>
 *   <li>Composite uniqueness constraint on {@code (user_id, idempotency_key)}</li>
 *   <li>Immutable execution association referencing {@code MigrationExecution}</li>
 *   <li>Terminal-only retention lifecycle: {@code expires_at == null} while active or recoverable;
 *       {@code expires_at = terminal_transition_time + 72 hours} upon reaching terminal state</li>
 * </ul>
 */
@Entity
@Table(
        name = "migration_idempotency_keys",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_idempotency_user_key", columnNames = {"user_id", "idempotency_key"})
        }
)
public class MigrationIdempotencyRecord {

    public static final Duration DEFAULT_TERMINAL_RETENTION = Duration.ofHours(72);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "idempotency_key", nullable = false, length = 255, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 128, updatable = false)
    private String requestFingerprint;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "response_status", nullable = false, updatable = false)
    private int responseStatus;

    @Column(name = "response_payload", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String responsePayload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected MigrationIdempotencyRecord() {
    }

    public MigrationIdempotencyRecord(
            UUID userId,
            String idempotencyKey,
            String requestFingerprint,
            UUID executionId,
            int responseStatus,
            String responsePayload,
            Instant createdAt,
            Instant expiresAt
    ) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        this.requestFingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint must not be null");
        this.executionId = Objects.requireNonNull(executionId, "executionId must not be null");
        this.responseStatus = responseStatus;
        this.responsePayload = Objects.requireNonNull(responsePayload, "responsePayload must not be null");
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.expiresAt = expiresAt;
    }

    public static MigrationIdempotencyRecord createActive(
            UUID userId,
            String idempotencyKey,
            String requestFingerprint,
            UUID executionId,
            int responseStatus,
            String responsePayload
    ) {
        return new MigrationIdempotencyRecord(
                userId,
                idempotencyKey,
                requestFingerprint,
                executionId,
                responseStatus,
                responsePayload,
                Instant.now(),
                null // Active/recoverable: expires_at is strictly NULL
        );
    }

    /**
     * Marks this record for expiry when its associated execution transitions to a terminal state.
     * Idempotent: repeated transitions will not extend or overwrite the existing retention period.
     *
     * @param terminalTransitionTime timestamp of terminal status transition
     */
    public void markTerminal(Instant terminalTransitionTime) {
        markTerminal(terminalTransitionTime, DEFAULT_TERMINAL_RETENTION);
    }

    /**
     * Marks this record for expiry with a specific retention duration.
     * Idempotent: only sets {@code expires_at} if it is currently null.
     *
     * @param terminalTransitionTime timestamp of terminal status transition
     * @param retention duration after terminal transition before cleanup is permitted
     */
    public void markTerminal(Instant terminalTransitionTime, Duration retention) {
        if (this.expiresAt == null) {
            Instant baseTime = terminalTransitionTime != null ? terminalTransitionTime : Instant.now();
            Duration duration = retention != null ? retention : DEFAULT_TERMINAL_RETENTION;
            this.expiresAt = baseTime.plus(duration);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public String getResponsePayload() {
        return responsePayload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
