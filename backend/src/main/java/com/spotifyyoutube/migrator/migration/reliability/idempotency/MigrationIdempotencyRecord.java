package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 *   <li>Database-backed atomic claim protocol: {@code status == PENDING} during leader preparation;
 *       {@code status == COMPLETED} upon durable execution dispatch</li>
 *   <li>Claim fencing: unique {@code claim_owner} token and monotonic {@code claim_generation} to prevent
 *       stale leaders from completing claims after expiration and takeover</li>
 *   <li>Lease renewal tracking via {@code heartbeat_at} and PostgreSQL clock timestamps</li>
 *   <li>Pre-persistence destination playlist binding to prevent duplicate YouTube playlist creation across crashes</li>
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
    public static final Duration DEFAULT_CLAIM_TIMEOUT = Duration.ofSeconds(60);
    public static final String STAGE_ACQUIRED = "ACQUIRED";
    public static final String STAGE_EXTERNAL_OPERATION_STARTED = "EXTERNAL_OPERATION_STARTED";
    public static final String STAGE_PLAYLIST_CREATED = "PLAYLIST_CREATED";

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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private IdempotencyClaimStatus status = IdempotencyClaimStatus.COMPLETED;

    @Column(name = "execution_id")
    private UUID executionId;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_payload", columnDefinition = "TEXT")
    private String responsePayload;

    @Column(name = "location_header", length = 2048)
    private String locationHeader;

    @Column(name = "review_session_id")
    private UUID reviewSessionId;

    @Column(name = "claim_owner", length = 128)
    private String claimOwner;

    @Column(name = "claim_generation", nullable = false)
    private Long claimGeneration = 1L;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "destination_playlist_id", length = 255)
    private String destinationPlaylistId;

    @Column(name = "claim_stage", nullable = false, length = 32)
    private String claimStage = STAGE_ACQUIRED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "claim_expires_at")
    private Instant claimExpiresAt;

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
        this(
                userId,
                idempotencyKey,
                requestFingerprint,
                IdempotencyClaimStatus.COMPLETED,
                executionId,
                responseStatus,
                responsePayload,
                null,
                null,
                null,
                1L,
                null,
                null,
                STAGE_ACQUIRED,
                createdAt,
                null,
                expiresAt
        );
    }

    public MigrationIdempotencyRecord(
            UUID userId,
            String idempotencyKey,
            String requestFingerprint,
            IdempotencyClaimStatus status,
            UUID executionId,
            Integer responseStatus,
            String responsePayload,
            String locationHeader,
            UUID reviewSessionId,
            Instant createdAt,
            Instant claimExpiresAt,
            Instant expiresAt
    ) {
        this(
                userId,
                idempotencyKey,
                requestFingerprint,
                status,
                executionId,
                responseStatus,
                responsePayload,
                locationHeader,
                reviewSessionId,
                null,
                1L,
                null,
                null,
                STAGE_ACQUIRED,
                createdAt,
                claimExpiresAt,
                expiresAt
        );
    }

    public MigrationIdempotencyRecord(
            UUID userId,
            String idempotencyKey,
            String requestFingerprint,
            IdempotencyClaimStatus status,
            UUID executionId,
            Integer responseStatus,
            String responsePayload,
            String locationHeader,
            UUID reviewSessionId,
            String claimOwner,
            Long claimGeneration,
            Instant heartbeatAt,
            String destinationPlaylistId,
            String claimStage,
            Instant createdAt,
            Instant claimExpiresAt,
            Instant expiresAt
    ) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        this.requestFingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint must not be null");
        this.status = status != null ? status : IdempotencyClaimStatus.COMPLETED;
        this.executionId = executionId;
        this.responseStatus = responseStatus;
        this.responsePayload = responsePayload;
        this.locationHeader = locationHeader;
        this.reviewSessionId = reviewSessionId;
        this.claimOwner = claimOwner;
        this.claimGeneration = claimGeneration != null ? claimGeneration : 1L;
        this.heartbeatAt = heartbeatAt;
        this.destinationPlaylistId = destinationPlaylistId;
        this.claimStage = claimStage != null ? claimStage : STAGE_ACQUIRED;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.claimExpiresAt = claimExpiresAt;
        this.expiresAt = expiresAt;
    }

    public static MigrationIdempotencyRecord createPending(
            UUID userId,
            String idempotencyKey,
            String requestFingerprint,
            UUID reviewSessionId,
            Instant claimExpiresAt
    ) {
        String ownerToken = UUID.randomUUID().toString();
        Instant now = Instant.now();
        return new MigrationIdempotencyRecord(
                userId,
                idempotencyKey,
                requestFingerprint,
                IdempotencyClaimStatus.PENDING,
                null,
                null,
                null,
                null,
                reviewSessionId,
                ownerToken,
                1L,
                now,
                null,
                STAGE_ACQUIRED,
                now,
                claimExpiresAt,
                null // Active/recoverable: expires_at is strictly NULL
        );
    }

    public static MigrationIdempotencyRecord createPending(
            UUID userId,
            String idempotencyKey,
            String requestFingerprint,
            UUID reviewSessionId,
            String claimOwner,
            long claimGeneration,
            Instant claimExpiresAt
    ) {
        Instant now = Instant.now();
        return new MigrationIdempotencyRecord(
                userId,
                idempotencyKey,
                requestFingerprint,
                IdempotencyClaimStatus.PENDING,
                null,
                null,
                null,
                null,
                reviewSessionId,
                claimOwner,
                claimGeneration,
                now,
                null,
                STAGE_ACQUIRED,
                now,
                claimExpiresAt,
                null
        );
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
                IdempotencyClaimStatus.COMPLETED,
                executionId,
                responseStatus,
                responsePayload,
                null,
                null,
                null,
                1L,
                null,
                null,
                STAGE_ACQUIRED,
                Instant.now(),
                null,
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

    public void markCompleted(
            UUID executionId,
            int responseStatus,
            String responsePayload,
            String locationHeader
    ) {
        this.status = IdempotencyClaimStatus.COMPLETED;
        this.executionId = executionId;
        this.responseStatus = responseStatus;
        this.responsePayload = responsePayload;
        this.locationHeader = locationHeader;
        this.claimExpiresAt = null;
    }

    public void markExternalOperationStarted() {
        this.claimStage = STAGE_EXTERNAL_OPERATION_STARTED;
    }

    public void recordDestinationPlaylist(String destinationPlaylistId) {
        this.destinationPlaylistId = destinationPlaylistId;
        this.claimStage = STAGE_PLAYLIST_CREATED;
    }

    public void setClaimExpiresAt(Instant claimExpiresAt) {
        this.claimExpiresAt = claimExpiresAt;
    }

    public void setClaimOwner(String claimOwner) {
        this.claimOwner = claimOwner;
    }

    public void setClaimGeneration(Long claimGeneration) {
        this.claimGeneration = claimGeneration;
    }

    public void setHeartbeatAt(Instant heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }

    public void setDestinationPlaylistId(String destinationPlaylistId) {
        this.destinationPlaylistId = destinationPlaylistId;
    }

    public void setClaimStage(String claimStage) {
        this.claimStage = claimStage;
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

    public IdempotencyClaimStatus getStatus() {
        return status;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponsePayload() {
        return responsePayload;
    }

    public String getLocationHeader() {
        return locationHeader;
    }

    public UUID getReviewSessionId() {
        return reviewSessionId;
    }

    public String getClaimOwner() {
        return claimOwner;
    }

    public Long getClaimGeneration() {
        return claimGeneration != null ? claimGeneration : 1L;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public String getDestinationPlaylistId() {
        return destinationPlaylistId;
    }

    public String getClaimStage() {
        return claimStage != null ? claimStage : STAGE_ACQUIRED;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getClaimExpiresAt() {
        return claimExpiresAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
