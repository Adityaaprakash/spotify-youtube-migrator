package com.spotifyyoutube.migrator.migration.execution.domain;

import com.spotifyyoutube.migrator.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

/**
 * Persisted audit record for an individual track's rollback operation (Phase 11F).
 * <p>
 * Durably records each attempted compensating provider deletion, the target provider playlist item ID,
 * timestamps, and confirmed or failed outcome.
 */
@Entity
@Table(name = "execution_rollback_records")
public class ExecutionRollbackRecord extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    private MigrationExecution execution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "track_record_id", nullable = false)
    private ExecutionTrackRecord trackRecord;

    @Column(name = "target_position", nullable = false)
    private int targetPosition;

    @Column(name = "destination_track_id", nullable = false)
    private String destinationTrackId;

    @Column(name = "destination_playlist_item_id", nullable = false)
    private String destinationPlaylistItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RollbackRecordStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    public ExecutionRollbackRecord() {
    }

    public ExecutionRollbackRecord(
            MigrationExecution execution,
            ExecutionTrackRecord trackRecord,
            RollbackRecordStatus status,
            String errorMessage
    ) {
        this.execution = Objects.requireNonNull(execution, "MigrationExecution must not be null");
        this.trackRecord = Objects.requireNonNull(trackRecord, "ExecutionTrackRecord must not be null");
        this.targetPosition = trackRecord.getTargetPosition();
        this.destinationTrackId = trackRecord.getDestinationTrackId();
        this.destinationPlaylistItemId = trackRecord.getDestinationPlaylistItemId() != null
                ? trackRecord.getDestinationPlaylistItemId()
                : "";
        this.status = Objects.requireNonNull(status, "RollbackRecordStatus must not be null");
        this.errorMessage = errorMessage;
        this.attemptedAt = Instant.now();
        this.completedAt = Instant.now();
    }

    public static ExecutionRollbackRecord success(
            MigrationExecution execution,
            ExecutionTrackRecord trackRecord
    ) {
        return new ExecutionRollbackRecord(execution, trackRecord, RollbackRecordStatus.ROLLED_BACK, null);
    }

    public static ExecutionRollbackRecord alreadyRolledBack(
            MigrationExecution execution,
            ExecutionTrackRecord trackRecord
    ) {
        return new ExecutionRollbackRecord(execution, trackRecord, RollbackRecordStatus.ALREADY_ROLLED_BACK, "Track was already rolled back");
    }

    public static ExecutionRollbackRecord failed(
            MigrationExecution execution,
            ExecutionTrackRecord trackRecord,
            String sanitizedErrorMessage
    ) {
        return new ExecutionRollbackRecord(execution, trackRecord, RollbackRecordStatus.FAILED, sanitizedErrorMessage);
    }

    // Getters

    public MigrationExecution getExecution() {
        return execution;
    }

    public ExecutionTrackRecord getTrackRecord() {
        return trackRecord;
    }

    public int getTargetPosition() {
        return targetPosition;
    }

    public String getDestinationTrackId() {
        return destinationTrackId;
    }

    public String getDestinationPlaylistItemId() {
        return destinationPlaylistItemId;
    }

    public RollbackRecordStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getVersion() {
        return version;
    }
}
