package com.spotifyyoutube.migrator.migration.execution.domain;

import com.spotifyyoutube.migrator.common.domain.BaseEntity;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.migration.reliability.scheduling.StaleLeaseOwnershipException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persisted execution domain entity representing a durable playlist migration execution (Phase 11E).
 * <p>
 * Binds an approved, completed {@link ReviewSession} to a confirmed destination playlist identity on a target platform.
 * Governs execution lifecycle states, concurrency claims, optimistic locking, and safe resume semantics.
 */
@Entity
@Table(
        name = "migration_executions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_migrationexecution_reviewsession", columnNames = {"review_session_id"})
        }
)
public class MigrationExecution extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_session_id", nullable = false)
    private ReviewSession reviewSession;

    @Column(name = "plan_id")
    private UUID planId;

    @Column(name = "source_playlist_id", nullable = false)
    private String sourcePlaylistId;

    @Column(name = "source_playlist_title", nullable = false, length = 500)
    private String sourcePlaylistTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "destination_platform", nullable = false)
    private Platform destinationPlatform = Platform.YOUTUBE;

    @Column(name = "destination_playlist_id")
    private String destinationPlaylistId;

    @Column(name = "destination_playlist_url", length = 2048)
    private String destinationPlaylistUrl;

    @Column(name = "playlist_creation_operation_id")
    private UUID playlistCreationOperationId;

    @Column(name = "playlist_creation_attempt_count", nullable = false)
    private int playlistCreationAttemptCount = 0;

    @Column(name = "playlist_creation_max_attempts", nullable = false)
    private int playlistCreationMaxAttempts = 3;

    @Column(name = "playlist_creation_next_attempt_at")
    private Instant playlistCreationNextAttemptAt;

    @Column(name = "playlist_creation_failure_code", length = 50)
    private String playlistCreationFailureCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MigrationExecutionStatus status = MigrationExecutionStatus.INITIALIZED;

    @Column(name = "total_approved_tracks", nullable = false)
    private int totalApprovedTracks = 0;

    @Column(name = "successful_insertions", nullable = false)
    private int successfulInsertions = 0;

    @Column(name = "failed_insertions", nullable = false)
    private int failedInsertions = 0;

    @Column(name = "uncertain_insertions", nullable = false)
    private int uncertainInsertions = 0;

    @Column(name = "skipped_insertions", nullable = false)
    private int skippedInsertions = 0;

    @Column(name = "rolled_back_insertions", nullable = false)
    private int rolledBackInsertions = 0;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "lease_generation", nullable = false)
    private Long leaseGeneration = 0L;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @OneToMany(mappedBy = "execution", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("targetPosition ASC")
    private List<ExecutionTrackRecord> trackRecords = new ArrayList<>();

    public MigrationExecution() {
    }

    public MigrationExecution(
            User user,
            ReviewSession reviewSession,
            ApprovedMigrationContext context,
            String destinationPlaylistId,
            String destinationPlaylistUrl
    ) {
        this.user = Objects.requireNonNull(user, "User must not be null");
        this.reviewSession = Objects.requireNonNull(reviewSession, "ReviewSession must not be null");
        Objects.requireNonNull(context, "ApprovedMigrationContext must not be null");

        if (destinationPlaylistId == null || destinationPlaylistId.isBlank()) {
            throw new IllegalArgumentException("destinationPlaylistId must not be blank");
        }

        this.planId = context.planId();
        this.sourcePlaylistId = context.sourcePlaylistId();
        this.sourcePlaylistTitle = context.sourcePlaylistTitle() != null ? context.sourcePlaylistTitle() : "Migrated Playlist";
        this.destinationPlatform = Platform.YOUTUBE;
        this.destinationPlaylistId = destinationPlaylistId.trim();
        this.destinationPlaylistUrl = destinationPlaylistUrl != null ? destinationPlaylistUrl.trim() : null;
        this.status = MigrationExecutionStatus.INITIALIZED;

        initializeTracks(context);
    }

    public MigrationExecution(
            User user,
            ReviewSession reviewSession,
            ApprovedMigrationContext context,
            String destinationPlaylistId,
            String destinationPlaylistUrl,
            MigrationExecutionStatus initialStatus
    ) {
        this(user, reviewSession, context, destinationPlaylistId, destinationPlaylistUrl);
        if (initialStatus != null) {
            if (initialStatus != MigrationExecutionStatus.INITIALIZED && initialStatus != MigrationExecutionStatus.QUEUED) {
                throw new IllegalArgumentException("Initial execution status must be INITIALIZED or QUEUED, got: " + initialStatus);
            }
            this.status = initialStatus;
        }
    }

    public MigrationExecution(
            ReviewSession reviewSession,
            User user,
            String destinationPlatform,
            String destinationPlaylistId,
            String destinationPlaylistUrl,
            String sourcePlaylistTitle,
            UUID planId
    ) {
        this.reviewSession = Objects.requireNonNull(reviewSession, "ReviewSession must not be null");
        this.user = Objects.requireNonNull(user, "User must not be null");
        if (destinationPlatform == null || destinationPlatform.isBlank()) {
            throw new IllegalArgumentException("Destination platform must not be blank");
        }
        if (destinationPlaylistId == null || destinationPlaylistId.isBlank()) {
            throw new IllegalArgumentException("Destination playlist ID must not be blank");
        }
        this.destinationPlatform = Platform.valueOf(destinationPlatform.trim().toUpperCase());
        this.destinationPlaylistId = destinationPlaylistId.trim();
        this.destinationPlaylistUrl = destinationPlaylistUrl != null ? destinationPlaylistUrl.trim() : null;
        this.sourcePlaylistId = reviewSession.getSourcePlaylistId() != null ? reviewSession.getSourcePlaylistId() : "sp_unknown";
        this.sourcePlaylistTitle = sourcePlaylistTitle != null ? sourcePlaylistTitle : "Migrated Playlist";
        this.planId = planId;
        this.status = MigrationExecutionStatus.INITIALIZED;
    }

    /**
     * Pre-allocates execution intent before initiating remote playlist creation (Phase 13F).
     * Binds user, session, and approved tracks while awaiting remote playlist identity.
     */
    public MigrationExecution(
            User user,
            ReviewSession reviewSession,
            ApprovedMigrationContext context,
            UUID playlistCreationOperationId
    ) {
        this.user = Objects.requireNonNull(user, "User must not be null");
        this.reviewSession = Objects.requireNonNull(reviewSession, "ReviewSession must not be null");
        Objects.requireNonNull(context, "ApprovedMigrationContext must not be null");
        this.playlistCreationOperationId = Objects.requireNonNull(playlistCreationOperationId, "playlistCreationOperationId must not be null");
        this.planId = context.planId();
        this.sourcePlaylistId = context.sourcePlaylistId();
        this.sourcePlaylistTitle = context.sourcePlaylistTitle() != null ? context.sourcePlaylistTitle() : "Migrated Playlist";
        this.destinationPlatform = Platform.YOUTUBE;
        this.destinationPlaylistId = null;
        this.destinationPlaylistUrl = null;
        this.status = MigrationExecutionStatus.ALLOCATING_DESTINATION;
        this.playlistCreationAttemptCount = 1;
        this.playlistCreationMaxAttempts = 3;

        initializeTracks(context);
    }

    /**
     * Initializes track records from the validated approved migration context.
     */
    public void initializeTracks(ApprovedMigrationContext context) {
        Objects.requireNonNull(context, "ApprovedMigrationContext must not be null");
        initializeTracks(context.approvedTracks());
    }

    /**
     * Initializes track records from an approved track collection.
     */
    public void initializeTracks(List<ApprovedTrack> approved) {
        Objects.requireNonNull(approved, "Approved tracks must not be null");
        if (approved.isEmpty()) {
            throw new IllegalArgumentException("Approved tracks must not be empty");
        }
        if (!this.trackRecords.isEmpty()) {
            throw new IllegalStateException("Track records are already initialized");
        }
        this.trackRecords.clear();
        this.totalApprovedTracks = approved.size();

        for (ApprovedTrack track : approved) {
            ExecutionTrackRecord record = new ExecutionTrackRecord(this, track);
            this.trackRecords.add(record);
        }

        this.successfulInsertions = 0;
        this.failedInsertions = 0;
        this.uncertainInsertions = 0;
        this.skippedInsertions = 0;
        this.rolledBackInsertions = 0;
    }

    /**
     * Transitions this execution to QUEUED status, waiting for background worker claiming (Phase 12B).
     * Only an execution in INITIALIZED status may transition to QUEUED.
     * Idempotent if already in QUEUED status.
     */
    public void markQueued() {
        if (this.status == MigrationExecutionStatus.QUEUED) {
            return;
        }
        if (this.status != MigrationExecutionStatus.INITIALIZED) {
            throw new InvalidStateException(String.format(
                    "Cannot transition execution %s to QUEUED: current status is %s, expected INITIALIZED",
                    getId(), this.status
            ));
        }
        this.status = MigrationExecutionStatus.QUEUED;
    }

    /**
     * Resets a QUEUED execution back to INITIALIZED status if worker dispatch was rejected (Phase 12C).
     * Prevents the execution from becoming an orphaned QUEUED job while preserving the destination
     * playlist, approved track records, and review session binding for safe retry or recovery.
     */
    public void resetToInitialized() {
        if (this.status != MigrationExecutionStatus.QUEUED) {
            throw new InvalidStateException(String.format(
                    "Cannot reset execution %s to INITIALIZED: current status is %s, expected QUEUED",
                    getId(), this.status
            ));
        }
        this.status = MigrationExecutionStatus.INITIALIZED;
    }

    /**
     * Claims this execution for active insertion processing on the current thread.
     * Prevents concurrent execution.
     */
    public void claimExecution() {
        if (this.status == MigrationExecutionStatus.RUNNING) {
            throw new InvalidStateException("Migration execution is already in progress by another request");
        }
        if (this.status.isTerminal()) {
            throw new InvalidStateException("Cannot claim execution: execution is in terminal status " + status);
        }

        this.status = MigrationExecutionStatus.RUNNING;
        if (this.startedAt == null) {
            this.startedAt = Instant.now();
        }
    }

    /**
     * Releases active execution claim, reconciling all per-track counts and determining the resulting status.
     */
    public void releaseExecution() {
        reconcileCounts();

        if (successfulInsertions == totalApprovedTracks && totalApprovedTracks > 0) {
            this.status = MigrationExecutionStatus.COMPLETED;
            this.completedAt = Instant.now();
        } else if (uncertainInsertions > 0 || hasStaleAdding()) {
            this.status = MigrationExecutionStatus.RECOVERY_REQUIRED;
        } else if (successfulInsertions == 0 && rolledBackInsertions > 0) {
            this.status = MigrationExecutionStatus.ROLLED_BACK;
            this.completedAt = Instant.now();
        } else if (rolledBackInsertions > 0) {
            this.status = MigrationExecutionStatus.PARTIALLY_ROLLED_BACK;
        } else if (successfulInsertions > 0) {
            this.status = MigrationExecutionStatus.PARTIALLY_COMPLETED;
        } else {
            this.status = MigrationExecutionStatus.FAILED;
            this.completedAt = Instant.now();
        }
    }

    /**
     * Reconciles summary counters directly from the persisted per-track records.
     */
    public void reconcileCounts() {
        int successful = 0;
        int failed = 0;
        int uncertain = 0;
        int skipped = 0;
        int rolledBack = 0;

        for (ExecutionTrackRecord record : trackRecords) {
            switch (record.getStatus()) {
                case ADDED -> successful++;
                case FAILED -> failed++;
                case UNCERTAIN -> uncertain++;
                case SKIPPED -> skipped++;
                case ROLLED_BACK -> rolledBack++;
                default -> {
                    // PENDING, ADDING, or ROLLING_BACK
                }
            }
        }

        this.successfulInsertions = successful;
        this.failedInsertions = failed;
        this.uncertainInsertions = uncertain;
        this.skippedInsertions = skipped;
        this.rolledBackInsertions = rolledBack;
    }

    /**
     * Default lease duration before an in-flight operation (ADDING or ROLLING_BACK) is classified as abandoned/stale.
     * Prevents active, slow provider operations from being prematurely classified as uncertain.
     */
    public static final Duration DEFAULT_STALE_THRESHOLD = Duration.ofMinutes(5);

    /**
     * Reconciles stale ADDING or ROLLING_BACK tracks detected after an application crash or process restart,
     * applying the default 5-minute lease threshold.
     *
     * @return true if stale crash state was detected and reconciled, false otherwise
     */
    public boolean reconcileStaleCrashState() {
        return reconcileStaleCrashState(DEFAULT_STALE_THRESHOLD);
    }

    /**
     * Reconciles stale ADDING or ROLLING_BACK tracks using a custom lease duration threshold.
     * In-flight tracks started within the lease window are preserved as active and not marked uncertain.
     *
     * @param staleThreshold lease duration threshold
     * @return true if stale tracks were found and transitioned to UNCERTAIN, false otherwise
     */
    public boolean reconcileStaleCrashState(Duration staleThreshold) {
        Duration threshold = staleThreshold != null ? staleThreshold : DEFAULT_STALE_THRESHOLD;
        Instant cutoff = Instant.now().minus(threshold);
        return reconcileStaleCrashState(cutoff);
    }

    /**
     * Reconciles stale ADDING or ROLLING_BACK tracks using a specific cutoff timestamp.
     *
     * @param cutoff timestamp threshold before which an in-flight operation is deemed abandoned
     * @return true if stale tracks were found and transitioned to UNCERTAIN, false otherwise
     */
    public boolean reconcileStaleCrashState(Instant cutoff) {
        boolean hadStale = false;
        boolean hasActiveInFlight = false;

        for (ExecutionTrackRecord record : trackRecords) {
            if (record.getStatus() == ExecutionTrackStatus.ADDING || record.getStatus() == ExecutionTrackStatus.ROLLING_BACK) {
                if (record.isStaleInFlight(cutoff)) {
                    record.reconcileStaleInFlight(cutoff);
                    hadStale = true;
                } else {
                    hasActiveInFlight = true;
                }
            }
        }

        if (hadStale) {
            this.status = MigrationExecutionStatus.RECOVERY_REQUIRED;
            reconcileCounts();
            return true;
        } else if (hasActiveInFlight) {
            // An active provider operation is currently in progress within the lease window.
            // Do not overwrite active execution state.
            return false;
        } else if (this.status == MigrationExecutionStatus.RUNNING) {
            // Execution marked RUNNING with no active in-flight tracks -> stale abandoned execution claim
            this.status = MigrationExecutionStatus.RECOVERY_REQUIRED;
            reconcileCounts();
            return true;
        }
        return false;
    }

    public void setStatus(MigrationExecutionStatus status) {
        this.status = Objects.requireNonNull(status, "Status must not be null");
    }

    public boolean isFullySuccessful() {
        return successfulInsertions == totalApprovedTracks && totalApprovedTracks > 0;
    }

    /**
     * Returns true if there are pending tracks that can be safely resumed.
     */
    public boolean hasPendingTracks() {
        return trackRecords.stream().anyMatch(record -> record.getStatus() == ExecutionTrackStatus.PENDING);
    }

    private boolean hasStaleAdding() {
        return trackRecords.stream().anyMatch(record ->
                record.getStatus() == ExecutionTrackStatus.ADDING || record.getStatus() == ExecutionTrackStatus.ROLLING_BACK);
    }

    public boolean canResume() {
        return this.status.canResume() && (hasPendingTracks() || this.status == MigrationExecutionStatus.INITIALIZED);
    }

    public boolean isTerminal() {
        return this.status.isTerminal();
    }

    public boolean isQueued() {
        return this.status == MigrationExecutionStatus.QUEUED;
    }

    // Getters

    public User getUser() {
        return user;
    }

    public ReviewSession getReviewSession() {
        return reviewSession;
    }

    public UUID getPlanId() {
        return planId;
    }

    public String getSourcePlaylistId() {
        return sourcePlaylistId;
    }

    public String getSourcePlaylistTitle() {
        return sourcePlaylistTitle;
    }

    public Platform getDestinationPlatform() {
        return destinationPlatform;
    }

    public String getDestinationPlaylistId() {
        return destinationPlaylistId;
    }

    public String getDestinationPlaylistUrl() {
        return destinationPlaylistUrl;
    }

    public UUID getPlaylistCreationOperationId() {
        return playlistCreationOperationId;
    }

    public void setPlaylistCreationOperationId(UUID playlistCreationOperationId) {
        this.playlistCreationOperationId = playlistCreationOperationId;
    }

    public int getPlaylistCreationAttemptCount() {
        return playlistCreationAttemptCount;
    }

    public void setPlaylistCreationAttemptCount(int playlistCreationAttemptCount) {
        this.playlistCreationAttemptCount = playlistCreationAttemptCount;
    }

    public int getPlaylistCreationMaxAttempts() {
        return playlistCreationMaxAttempts;
    }

    public void setPlaylistCreationMaxAttempts(int playlistCreationMaxAttempts) {
        this.playlistCreationMaxAttempts = playlistCreationMaxAttempts;
    }

    public Instant getPlaylistCreationNextAttemptAt() {
        return playlistCreationNextAttemptAt;
    }

    public void setPlaylistCreationNextAttemptAt(Instant playlistCreationNextAttemptAt) {
        this.playlistCreationNextAttemptAt = playlistCreationNextAttemptAt;
    }

    public String getPlaylistCreationFailureCode() {
        return playlistCreationFailureCode;
    }

    public void setPlaylistCreationFailureCode(String playlistCreationFailureCode) {
        this.playlistCreationFailureCode = playlistCreationFailureCode;
    }

    public void incrementPlaylistCreationAttempt() {
        this.playlistCreationAttemptCount++;
    }

    public void recordPlaylistCreationFailure(String failureCode, Instant nextAttemptAt) {
        this.playlistCreationFailureCode = failureCode;
        this.playlistCreationNextAttemptAt = nextAttemptAt;
    }

    public void markPlaylistCreated(String destinationPlaylistId, String destinationPlaylistUrl, MigrationExecutionStatus nextStatus) {
        if (this.status != MigrationExecutionStatus.ALLOCATING_DESTINATION
                && this.status != MigrationExecutionStatus.RECOVERY_REQUIRED
                && this.status != MigrationExecutionStatus.INITIALIZED) {
            throw new InvalidStateException("Cannot mark playlist created from status: " + this.status);
        }
        if (destinationPlaylistId == null || destinationPlaylistId.isBlank()) {
            throw new IllegalArgumentException("destinationPlaylistId must not be blank");
        }
        this.destinationPlaylistId = destinationPlaylistId.trim();
        this.destinationPlaylistUrl = destinationPlaylistUrl != null ? destinationPlaylistUrl.trim() : null;
        this.status = nextStatus != null ? nextStatus : MigrationExecutionStatus.INITIALIZED;
        this.playlistCreationFailureCode = null;
    }

    public MigrationExecutionStatus getStatus() {
        return status;
    }

    public int getTotalApprovedTracks() {
        return totalApprovedTracks;
    }

    public int getSuccessfulInsertions() {
        return successfulInsertions;
    }

    public int getFailedInsertions() {
        return failedInsertions;
    }

    public int getUncertainInsertions() {
        return uncertainInsertions;
    }

    public int getSkippedInsertions() {
        return skippedInsertions;
    }

    public int getRolledBackInsertions() {
        return rolledBackInsertions;
    }

    public boolean hasFailedTracks() {
        return trackRecords.stream().anyMatch(record -> record.getStatus() == ExecutionTrackStatus.FAILED);
    }

    public boolean hasAddedTracks() {
        return trackRecords.stream().anyMatch(record -> record.getStatus() == ExecutionTrackStatus.ADDED);
    }

    public boolean canRollback() {
        return this.status != MigrationExecutionStatus.RUNNING
                && this.status != MigrationExecutionStatus.ROLLED_BACK
                && (hasAddedTracks() || rolledBackInsertions > 0);
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getVersion() {
        return version;
    }

    public List<ExecutionTrackRecord> getTrackRecords() {
        return Collections.unmodifiableList(trackRecords);
    }

    public List<ExecutionTrackRecord> getMutableTrackRecords() {
        return trackRecords;
    }

    // --- Phase 13D-A Durable Lease & Fencing Domain Methods ---

    /**
     * Atomically acquires worker lease ownership on this execution with a monotonically incremented fencing generation.
     *
     * @param workerId worker node/thread identity
     * @param leaseUntil lease expiration timestamp
     * @param heartbeatAt heartbeat timestamp
     * @return the newly assigned lease generation fencing token
     */
    public long acquireLease(String workerId, Instant leaseUntil, Instant heartbeatAt) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");

        Instant now = heartbeatAt != null ? heartbeatAt : Instant.now();
        if (this.status == MigrationExecutionStatus.RUNNING && isLeaseActive(now) && !isOwner(workerId, this.leaseGeneration != null ? this.leaseGeneration : 0L)) {
            throw new InvalidStateException(String.format(
                    "Cannot acquire lease on execution %s: active lease held by worker '%s' until %s",
                    getId(), this.leaseOwner, this.leaseUntil
            ));
        }
        if (this.status.isTerminal()) {
            throw new InvalidStateException("Cannot acquire lease on terminal execution: " + this.status);
        }

        this.leaseOwner = workerId.trim();
        this.leaseUntil = leaseUntil;
        this.heartbeatAt = now;
        this.leaseGeneration = (this.leaseGeneration != null ? this.leaseGeneration : 0L) + 1L;
        this.status = MigrationExecutionStatus.RUNNING;
        this.nextAttemptAt = null;
        if (this.startedAt == null) {
            this.startedAt = now;
        }

        return this.leaseGeneration;
    }

    /**
     * Renews the active worker lease heartbeat conditionally on fencing generation ownership.
     *
     * @param workerId worker node/thread identity
     * @param fencingGeneration acquired fencing generation
     * @param newLeaseUntil new lease expiration timestamp
     * @param heartbeatAt current heartbeat timestamp
     */
    public void renewHeartbeat(String workerId, long fencingGeneration, Instant newLeaseUntil, Instant heartbeatAt) {
        if (!isOwner(workerId, fencingGeneration)) {
            throw new StaleLeaseOwnershipException(
                    getId(),
                    workerId,
                    fencingGeneration,
                    this.leaseGeneration,
                    String.format(
                            "Cannot renew heartbeat on execution %s: worker '%s' with generation %d is not the current owner (owner='%s', gen=%d)",
                            getId(), workerId, fencingGeneration, this.leaseOwner, this.leaseGeneration
                    )
            );
        }
        Objects.requireNonNull(newLeaseUntil, "newLeaseUntil must not be null");
        this.leaseUntil = newLeaseUntil;
        this.heartbeatAt = heartbeatAt != null ? heartbeatAt : Instant.now();
    }

    /**
     * Cooperatively releases worker lease ownership upon execution completion or yield.
     * Guarded against active in-flight track operations (ADDING or ROLLING_BACK).
     *
     * @param workerId worker node/thread identity
     * @param fencingGeneration acquired fencing generation
     */
    public void releaseLease(String workerId, long fencingGeneration) {
        if (!isOwner(workerId, fencingGeneration)) {
            throw new StaleLeaseOwnershipException(
                    getId(),
                    workerId,
                    fencingGeneration,
                    this.leaseGeneration,
                    String.format(
                            "Cannot release lease on execution %s: worker '%s' with generation %d is not the current owner (owner='%s', gen=%d)",
                            getId(), workerId, fencingGeneration, this.leaseOwner, this.leaseGeneration
                    )
            );
        }
        if (hasActiveInFlightTracks()) {
            throw new InvalidStateException(String.format(
                    "Cannot release lease on execution %s: tracks are actively in-flight",
                    getId()
            ));
        }
        this.leaseOwner = null;
        this.leaseUntil = null;
    }

    /**
     * Cooperatively releases worker lease and transitions the execution back to QUEUED with a future retry timestamp.
     * Guarded against active in-flight track operations (ADDING or ROLLING_BACK).
     *
     * @param workerId worker node/thread identity
     * @param fencingGeneration acquired fencing generation
     * @param nextAttemptAt earliest timestamp when this execution becomes eligible for scheduler claiming
     */
    public void releaseLeaseAndScheduleRetry(String workerId, long fencingGeneration, Instant nextAttemptAt) {
        if (!isOwner(workerId, fencingGeneration)) {
            throw new StaleLeaseOwnershipException(
                    getId(),
                    workerId,
                    fencingGeneration,
                    this.leaseGeneration,
                    String.format(
                            "Cannot release lease and schedule retry on execution %s: worker '%s' with generation %d is not the current owner (owner='%s', gen=%d)",
                            getId(), workerId, fencingGeneration, this.leaseOwner, this.leaseGeneration
                    )
            );
        }
        if (hasActiveInFlightTracks()) {
            throw new InvalidStateException(String.format(
                    "Cannot release lease and schedule retry on execution %s: tracks are actively in-flight",
                    getId()
            ));
        }
        this.leaseOwner = null;
        this.leaseUntil = null;
        this.status = MigrationExecutionStatus.QUEUED;
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
    }

    /**
     * Returns true if any track in this execution is currently in-flight (ADDING or ROLLING_BACK).
     *
     * @return true if active in-flight tracks exist
     */
    public boolean hasActiveInFlightTracks() {
        return trackRecords.stream().anyMatch(record ->
                record.getStatus() == ExecutionTrackStatus.ADDING || record.getStatus() == ExecutionTrackStatus.ROLLING_BACK);
    }

    /**
     * Finds the earliest deferred retry timestamp among all pending tracks in this execution.
     *
     * @return earliest nextAttemptAt timestamp, or empty if no pending tracks are deferred
     */
    public Optional<Instant> getEarliestDeferredRetry() {
        return trackRecords.stream()
                .filter(t -> t.getStatus() == ExecutionTrackStatus.PENDING && t.getNextAttemptAt() != null)
                .map(ExecutionTrackRecord::getNextAttemptAt)
                .min(Comparator.naturalOrder());
    }

    /**
     * Returns true if a valid lease is currently held by a worker and has not expired.
     *
     * @param now reference timestamp
     * @return true if lease is active
     */
    public boolean isLeaseActive(Instant now) {
        Instant ref = now != null ? now : Instant.now();
        return this.leaseOwner != null && this.leaseUntil != null && this.leaseUntil.isAfter(ref);
    }

    /**
     * Returns true if a lease was assigned but its duration has elapsed.
     *
     * @param now reference timestamp
     * @return true if lease is expired
     */
    public boolean isLeaseExpired(Instant now) {
        Instant ref = now != null ? now : Instant.now();
        return this.leaseOwner != null && this.leaseUntil != null && !this.leaseUntil.isAfter(ref);
    }

    /**
     * Validates whether the given worker ID and generation match the current lease ownership.
     *
     * @param workerId worker identifier
     * @param fencingGeneration generation token
     * @return true if caller is the active lease owner
     */
    public boolean isOwner(String workerId, long fencingGeneration) {
        return workerId != null
                && Objects.equals(this.leaseOwner, workerId.trim())
                && this.leaseGeneration != null
                && this.leaseGeneration == fencingGeneration;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Instant leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public void setHeartbeatAt(Instant heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }

    public Long getLeaseGeneration() {
        return leaseGeneration != null ? leaseGeneration : 0L;
    }

    public void setLeaseGeneration(Long leaseGeneration) {
        this.leaseGeneration = leaseGeneration != null ? leaseGeneration : 0L;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }
}
