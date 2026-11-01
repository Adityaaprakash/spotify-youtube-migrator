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
        if (status != ExecutionTrackStatus.ADDING) {
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
     * Reconciles a stale ADDING state detected upon application startup or recovery.
     * Marks as UNCERTAIN rather than PENDING to prevent duplicate insertion.
     */
    public void reconcileStaleAdding() {
        if (this.status == ExecutionTrackStatus.ADDING) {
            this.status = ExecutionTrackStatus.UNCERTAIN;
            this.errorMessage = "Operation interrupted before provider confirmation was recorded (stale ADDING recovery)";
            this.completedAt = Instant.now();
        }
    }

    /**
     * Marks this track as rolled back following confirmed removal from the destination playlist (Phase 11F).
     */
    public void markRolledBack(String reason) {
        if (status != ExecutionTrackStatus.ADDED) {
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

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getVersion() {
        return version;
    }
}
