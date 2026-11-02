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
 * Persisted audit record for an individual track's remote reconciliation attempt (Phase 11G).
 * <p>
 * Durably records the evidence, previous and resolved statuses, timestamps,
 * and outcome of inspecting remote destination state.
 */
@Entity
@Table(name = "execution_reconciliation_records")
public class ExecutionReconciliationRecord extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    private MigrationExecution execution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "track_record_id", nullable = false)
    private ExecutionTrackRecord trackRecord;

    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_type", nullable = false)
    private ReconciliationType reconciliationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_track_status", nullable = false)
    private ExecutionTrackStatus previousTrackStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolved_track_status", nullable = false)
    private ExecutionTrackStatus resolvedTrackStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_outcome", nullable = false)
    private ReconciliationOutcome reconciliationOutcome;

    @Column(name = "destination_playlist_id", nullable = false)
    private String destinationPlaylistId;

    @Column(name = "destination_playlist_item_id")
    private String destinationPlaylistItemId;

    @Column(name = "destination_track_id")
    private String destinationTrackId;

    @Column(name = "evidence_summary", columnDefinition = "TEXT")
    private String evidenceSummary;

    @Column(name = "evidence_timestamp")
    private Instant evidenceTimestamp;

    @Column(name = "inspection_started_at", nullable = false)
    private Instant inspectionStartedAt;

    @Column(name = "inspection_completed_at", nullable = false)
    private Instant inspectionCompletedAt;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "sanitized_error_message", columnDefinition = "TEXT")
    private String sanitizedErrorMessage;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    public ExecutionReconciliationRecord() {
    }

    public ExecutionReconciliationRecord(
            MigrationExecution execution,
            ExecutionTrackRecord trackRecord,
            ReconciliationType reconciliationType,
            ExecutionTrackStatus previousTrackStatus,
            ExecutionTrackStatus resolvedTrackStatus,
            ReconciliationOutcome reconciliationOutcome,
            String destinationPlaylistId,
            String destinationPlaylistItemId,
            String destinationTrackId,
            String evidenceSummary,
            Instant evidenceTimestamp,
            Instant inspectionStartedAt,
            Instant inspectionCompletedAt,
            String failureCode,
            String sanitizedErrorMessage
    ) {
        this.execution = Objects.requireNonNull(execution, "MigrationExecution must not be null");
        this.trackRecord = Objects.requireNonNull(trackRecord, "ExecutionTrackRecord must not be null");
        this.reconciliationType = Objects.requireNonNull(reconciliationType, "ReconciliationType must not be null");
        this.previousTrackStatus = Objects.requireNonNull(previousTrackStatus, "previousTrackStatus must not be null");
        this.resolvedTrackStatus = Objects.requireNonNull(resolvedTrackStatus, "resolvedTrackStatus must not be null");
        this.reconciliationOutcome = Objects.requireNonNull(reconciliationOutcome, "ReconciliationOutcome must not be null");
        this.destinationPlaylistId = Objects.requireNonNull(destinationPlaylistId, "destinationPlaylistId must not be null");
        this.destinationPlaylistItemId = destinationPlaylistItemId;
        this.destinationTrackId = destinationTrackId;
        this.evidenceSummary = evidenceSummary;
        this.evidenceTimestamp = evidenceTimestamp;
        this.inspectionStartedAt = Objects.requireNonNull(inspectionStartedAt, "inspectionStartedAt must not be null");
        this.inspectionCompletedAt = Objects.requireNonNull(inspectionCompletedAt, "inspectionCompletedAt must not be null");
        this.failureCode = failureCode;
        this.sanitizedErrorMessage = sanitizedErrorMessage;
    }

    // Getters

    public MigrationExecution getExecution() {
        return execution;
    }

    public ExecutionTrackRecord getTrackRecord() {
        return trackRecord;
    }

    public ReconciliationType getReconciliationType() {
        return reconciliationType;
    }

    public ExecutionTrackStatus getPreviousTrackStatus() {
        return previousTrackStatus;
    }

    public ExecutionTrackStatus getResolvedTrackStatus() {
        return resolvedTrackStatus;
    }

    public ReconciliationOutcome getReconciliationOutcome() {
        return reconciliationOutcome;
    }

    public String getDestinationPlaylistId() {
        return destinationPlaylistId;
    }

    public String getDestinationPlaylistItemId() {
        return destinationPlaylistItemId;
    }

    public String getDestinationTrackId() {
        return destinationTrackId;
    }

    public String getEvidenceSummary() {
        return evidenceSummary;
    }

    public Instant getEvidenceTimestamp() {
        return evidenceTimestamp;
    }

    public Instant getInspectionStartedAt() {
        return inspectionStartedAt;
    }

    public Instant getInspectionCompletedAt() {
        return inspectionCompletedAt;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getSanitizedErrorMessage() {
        return sanitizedErrorMessage;
    }

    public Long getVersion() {
        return version;
    }
}
