package com.spotifyyoutube.migrator.migration.execution.domain;

import com.spotifyyoutube.migrator.common.domain.BaseEntity;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

/**
 * Persisted track execution record representing the durable outcome of inserting an individual approved track
 * into a destination playlist (Phase 11E).
 */
@Entity
@Table(
        name = "execution_track_records",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_executiontrack_execution_position", columnNames = {"execution_id", "target_position"})
        }
)
public class ExecutionTrackRecord extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    private MigrationExecution execution;

    @Column(name = "target_position", nullable = false)
    private int targetPosition;

    @Column(name = "source_position", nullable = false)
    private int sourcePosition;

    @Column(name = "source_track_id", nullable = false)
    private String sourceTrackId;

    @Column(name = "source_title", nullable = false, length = 500)
    private String sourceTitle;

    @Column(name = "source_artist", nullable = false, length = 500)
    private String sourceArtist;

    @Column(name = "destination_track_id", nullable = false)
    private String destinationTrackId;

    @Column(name = "destination_playlist_item_id")
    private String destinationPlaylistItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ExecutionTrackStatus status = ExecutionTrackStatus.PENDING;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    public ExecutionTrackRecord() {
    }

    public ExecutionTrackRecord(
            MigrationExecution execution,
            ApprovedTrack approvedTrack
    ) {
        this.execution = Objects.requireNonNull(execution, "MigrationExecution must not be null");
        Objects.requireNonNull(approvedTrack, "ApprovedTrack must not be null");

        this.targetPosition = approvedTrack.targetPosition();
        this.sourcePosition = approvedTrack.sourcePosition();
        this.sourceTrackId = Objects.requireNonNull(approvedTrack.sourceTrackId(), "sourceTrackId must not be null");
        this.sourceTitle = approvedTrack.sourceTitle() != null ? approvedTrack.sourceTitle() : "Unknown Title";
        this.sourceArtist = approvedTrack.sourceArtist() != null ? approvedTrack.sourceArtist() : "Unknown Artist";
        this.destinationTrackId = Objects.requireNonNull(approvedTrack.destinationTrackId(), "destinationTrackId must not be null");
        this.status = ExecutionTrackStatus.PENDING;
        this.attemptCount = 0;
    }

    /**
     * Claims this track for insertion immediately prior to dispatching to the external destination provider.
     */
    public void markAdding() {
        if (!status.isEligibleForAttempt()) {
            throw new InvalidStateException(String.format(
                    "Cannot mark track at position %d as ADDING; current status is %s",
                    targetPosition, status
            ));
        }
        this.status = ExecutionTrackStatus.ADDING;
        this.startedAt = Instant.now();
        this.attemptCount++;
    }

    /**
     * Confirms successful insertion of this track by the destination provider.
     */
    public void markAdded(String playlistItemId) {
        if (status != ExecutionTrackStatus.ADDING) {
            throw new InvalidStateException(String.format(
                    "Cannot mark track at position %d as ADDED; current status is %s",
                    targetPosition, status
            ));
        }
        if (playlistItemId == null || playlistItemId.isBlank()) {
            throw new IllegalArgumentException("playlistItemId must not be blank on confirmed insertion");
        }
        this.status = ExecutionTrackStatus.ADDED;
        this.destinationPlaylistItemId = playlistItemId.trim();
        this.completedAt = Instant.now();
        this.errorMessage = null;
    }

    /**
     * Records confirmed rejection of this track insertion by the destination provider.
     */
    public void markFailed(String sanitizedErrorMessage) {
        if (status != ExecutionTrackStatus.ADDING) {
            throw new InvalidStateException(String.format(
                    "Cannot mark track at position %d as FAILED; current status is %s",
                    targetPosition, status
            ));
        }
        this.status = ExecutionTrackStatus.FAILED;
        this.errorMessage = sanitizedErrorMessage;
        this.completedAt = Instant.now();
    }

    /**
     * Records an ambiguous remote outcome (e.g. network timeout or connection reset).
     */
    public void markUncertain(String sanitizedErrorMessage) {
        if (status != ExecutionTrackStatus.ADDING && status != ExecutionTrackStatus.ROLLING_BACK) {
            throw new InvalidStateException(String.format(
                    "Cannot mark track at position %d as UNCERTAIN; current status is %s",
                    targetPosition, status
            ));
        }
        this.status = ExecutionTrackStatus.UNCERTAIN;
        this.errorMessage = sanitizedErrorMessage;
        this.completedAt = Instant.now();
    }

    /**
     * Skips this track to preserve playlist order following a prior failure or uncertain outcome.
     */
    public void markSkipped(String reason) {
        if (status != ExecutionTrackStatus.PENDING) {
            throw new InvalidStateException(String.format(
                    "Cannot skip track at position %d; current status is %s",
                    targetPosition, status
            ));
        }
        this.status = ExecutionTrackStatus.SKIPPED;
        this.errorMessage = reason;
        this.completedAt = Instant.now();
    }

    /**
     * Reconciles a stale ADDING or ROLLING_BACK state detected upon application startup or recovery.
     * Marks as UNCERTAIN rather than PENDING to prevent duplicate mutations.
     */
    public void reconcileStaleAdding() {
        if (this.status == ExecutionTrackStatus.ADDING || this.status == ExecutionTrackStatus.ROLLING_BACK) {
            String prior = this.status.name();
            this.status = ExecutionTrackStatus.UNCERTAIN;
            this.errorMessage = "Operation interrupted before provider confirmation was recorded (stale " + prior + " recovery)";
            this.completedAt = Instant.now();
        }
    }

    /**
     * Returns true if this track is currently in-flight (ADDING or ROLLING_BACK) and its startedAt timestamp
     * is older than the specified cutoff, indicating an abandoned or crashed operation.
     *
     * @param cutoff timestamp threshold before which an in-flight operation is deemed abandoned
     * @return true if in-flight and started before cutoff, false otherwise
     */
    public boolean isStaleInFlight(Instant cutoff) {
        if (status != ExecutionTrackStatus.ADDING && status != ExecutionTrackStatus.ROLLING_BACK) {
            return false;
        }
        if (startedAt == null) {
            return true;
        }
        return startedAt.isBefore(cutoff);
    }

    /**
     * Reconciles a stale in-flight state if older than the cutoff threshold.
     * Transitions status to UNCERTAIN to prevent duplicate remote mutations.
     *
     * @param cutoff timestamp threshold before which an in-flight operation is deemed abandoned
     * @return true if reconciled, false if not stale or not in-flight
     */
    public boolean reconcileStaleInFlight(Instant cutoff) {
        if (isStaleInFlight(cutoff)) {
            String prior = this.status.name();
            this.status = ExecutionTrackStatus.UNCERTAIN;
            this.errorMessage = "Operation interrupted or lease expired before provider confirmation was recorded (stale " + prior + " recovery)";
            this.completedAt = Instant.now();
            return true;
        }
        return false;
    }

    /**
     * Marks this track as claimed for compensating deletion (Phase 11F).
     * Prevents concurrent duplicate remote DELETE requests.
     */
    public void markRollingBack() {
        if (status != ExecutionTrackStatus.ADDED) {
            throw new InvalidStateException(String.format(
                    "Cannot mark track at position %d as ROLLING_BACK; current status is %s",
                    targetPosition, status
            ));
        }
        this.status = ExecutionTrackStatus.ROLLING_BACK;
        this.startedAt = Instant.now();
        this.errorMessage = "Compensating deletion in progress";
    }

    /**
     * Reverts a failed rollback attempt from ROLLING_BACK back to ADDED (Phase 11F).
     */
    public void revertRollingBackToAdded(String failureReason) {
        if (status != ExecutionTrackStatus.ROLLING_BACK) {
            throw new InvalidStateException(String.format(
                    "Cannot revert track at position %d to ADDED; current status is %s",
                    targetPosition, status
            ));
        }
        this.status = ExecutionTrackStatus.ADDED;
        this.errorMessage = failureReason;
    }

    /**
     * Marks this track as rolled back following confirmed removal from the destination playlist (Phase 11F).
     */
    public void markRolledBack(String reason) {
        if (status != ExecutionTrackStatus.ADDED && status != ExecutionTrackStatus.ROLLING_BACK) {
            throw new InvalidStateException(String.format(
                    "Cannot mark track at position %d as ROLLED_BACK; current status is %s",
                    targetPosition, status
            ));
        }
        this.status = ExecutionTrackStatus.ROLLED_BACK;
        this.errorMessage = reason != null ? reason : "Rolled back: removed from destination playlist";
        this.completedAt = Instant.now();
    }

    /**
     * Resets a FAILED or SKIPPED track for controlled recovery execution (Phase 11F).
     */
    public void resetForRecovery() {
        if (status != ExecutionTrackStatus.FAILED && status != ExecutionTrackStatus.SKIPPED) {
            throw new InvalidStateException(String.format(
                    "Cannot reset track at position %d for recovery; current status is %s",
                    targetPosition, status
            ));
        }
        this.status = ExecutionTrackStatus.PENDING;
        this.errorMessage = null;
        this.startedAt = null;
        this.completedAt = null;
    }

    /**
     * Confirms that an uncertain insertion was applied by the remote provider (Phase 11G).
     * Transitions track from UNCERTAIN or ADDING to ADDED with verified playlist item ID.
     */
    public void reconcileConfirmedAdded(String playlistItemId, String reason) {
        if (status != ExecutionTrackStatus.UNCERTAIN && status != ExecutionTrackStatus.ADDING) {
            throw new InvalidStateException(String.format(
                    "Cannot reconcile track at position %d as ADDED; current status is %s",
                    targetPosition, status
            ));
        }
        if (playlistItemId == null || playlistItemId.isBlank()) {
            throw new IllegalArgumentException("playlistItemId must not be blank on confirmed insertion reconciliation");
        }
        this.status = ExecutionTrackStatus.ADDED;
        this.destinationPlaylistItemId = playlistItemId.trim();
        this.errorMessage = null;
        this.completedAt = Instant.now();
    }

    /**
     * Confirms that an uncertain insertion was NOT applied by the remote provider (Phase 11G).
     * Transitions track from UNCERTAIN or ADDING to FAILED so it can be recovered safely via Phase 11F recovery.
     */
    public void reconcileConfirmedNotInserted(String reason) {
        if (status != ExecutionTrackStatus.UNCERTAIN && status != ExecutionTrackStatus.ADDING) {
            throw new InvalidStateException(String.format(
                    "Cannot reconcile track at position %d as NOT_INSERTED; current status is %s",
                    targetPosition, status
            ));
        }
        this.status = ExecutionTrackStatus.FAILED;
        this.errorMessage = reason != null ? reason : "Reconciled: confirmed absent from destination playlist";
        this.completedAt = Instant.now();
    }

    /**
     * Confirms that an uncertain rollback deletion succeeded on the remote provider (Phase 11G).
     * Transitions track from UNCERTAIN or ROLLING_BACK to ROLLED_BACK.
     */
    public void reconcileConfirmedDeleted(String reason) {
        if (status != ExecutionTrackStatus.UNCERTAIN && status != ExecutionTrackStatus.ROLLING_BACK) {
            throw new InvalidStateException(String.format(
                    "Cannot reconcile track at position %d as DELETED; current status is %s",
                    targetPosition, status
            ));
        }
        this.status = ExecutionTrackStatus.ROLLED_BACK;
        this.errorMessage = reason != null ? reason : "Reconciled: confirmed absent from destination playlist (rollback verified)";
        this.completedAt = Instant.now();
    }

    /**
     * Confirms that an item targeted for rollback is still present on the remote provider (Phase 11G).
     * Transitions track from UNCERTAIN or ROLLING_BACK back to ADDED, allowing future rollback retry.
     */
    public void reconcileConfirmedPresent(String reason) {
        if (status != ExecutionTrackStatus.UNCERTAIN && status != ExecutionTrackStatus.ROLLING_BACK) {
            throw new InvalidStateException(String.format(
                    "Cannot reconcile track at position %d as PRESENT; current status is %s",
                    targetPosition, status
            ));
        }
        this.status = ExecutionTrackStatus.ADDED;
        this.errorMessage = reason != null ? reason : "Reconciled: item confirmed still present in destination playlist (rollback not applied)";
        this.completedAt = Instant.now();
    }

    /**
     * Records that reconciliation evidence is ambiguous or provider inspection failed (Phase 11G).
     * Preserves UNCERTAIN status and updates diagnostic error message.
     */
    public void reconcileAmbiguous(String reason) {
        if (status != ExecutionTrackStatus.UNCERTAIN && status != ExecutionTrackStatus.ADDING && status != ExecutionTrackStatus.ROLLING_BACK) {
            throw new InvalidStateException(String.format(
                    "Cannot record ambiguity for track at position %d; current status is %s",
                    targetPosition, status
            ));
        }
        this.status = ExecutionTrackStatus.UNCERTAIN;
        this.errorMessage = reason != null ? reason : "Reconciliation inconclusive: insufficient evidence";
        this.completedAt = Instant.now();
    }

    // Getters

    public MigrationExecution getExecution() {
        return execution;
    }

    public void setExecution(MigrationExecution execution) {
        this.execution = execution;
    }

    public int getTargetPosition() {
        return targetPosition;
    }

    public int getSourcePosition() {
        return sourcePosition;
    }

    public String getSourceTrackId() {
        return sourceTrackId;
    }

    public String getSourceTitle() {
        return sourceTitle;
    }

    public String getSourceArtist() {
        return sourceArtist;
    }

    public String getDestinationTrackId() {
        return destinationTrackId;
    }

    public String getDestinationPlaylistItemId() {
        return destinationPlaylistItemId;
    }

    public ExecutionTrackStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getVersion() {
        return version;
    }
}
