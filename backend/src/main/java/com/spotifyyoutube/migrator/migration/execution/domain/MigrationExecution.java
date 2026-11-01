package com.spotifyyoutube.migrator.migration.execution.domain;

import com.spotifyyoutube.migrator.common.domain.BaseEntity;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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

    @Column(name = "destination_playlist_id", nullable = false)
    private String destinationPlaylistId;

    @Column(name = "destination_playlist_url", length = 2048)
    private String destinationPlaylistUrl;

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
     * Reconciles stale ADDING or ROLLING_BACK tracks detected after an application crash or process restart.
     *
     * @return true if stale crash state was detected and reconciled, false otherwise
     */
    public boolean reconcileStaleCrashState() {
        boolean hadStale = false;
        for (ExecutionTrackRecord record : trackRecords) {
            if (record.getStatus() == ExecutionTrackStatus.ADDING || record.getStatus() == ExecutionTrackStatus.ROLLING_BACK) {
                record.reconcileStaleAdding();
                hadStale = true;
            }
        }

        if (hadStale || this.status == MigrationExecutionStatus.RUNNING) {
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
        return this.status.canResume() && (hasPendingTracks() || this.status == MigrationExecutionStatus.RECOVERY_REQUIRED);
    }

    public boolean isTerminal() {
        return this.status.isTerminal();
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
}
