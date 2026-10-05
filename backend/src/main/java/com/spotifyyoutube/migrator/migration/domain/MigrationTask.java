package com.spotifyyoutube.migrator.migration.domain;

import com.spotifyyoutube.migrator.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "migration_tasks")
public class MigrationTask extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private MigrationJob job;

    @Column(name = "source_track_id", nullable = false)
    private String sourceTrackId;

    @Column(name = "target_track_id")
    private String targetTrackId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MigrationStatus status = MigrationStatus.PENDING;

    @Column(name = "error_message")
    private String errorMessage;

    public MigrationTask() {
    }

    public MigrationJob getJob() {
        return job;
    }

    public void setJob(MigrationJob job) {
        this.job = job;
    }

    public String getSourceTrackId() {
        return sourceTrackId;
    }

    public void setSourceTrackId(String sourceTrackId) {
        this.sourceTrackId = sourceTrackId;
    }

    public String getTargetTrackId() {
        return targetTrackId;
    }

    public void setTargetTrackId(String targetTrackId) {
        this.targetTrackId = targetTrackId;
    }

    public MigrationStatus getStatus() {
        return status;
    }

    public void setStatus(MigrationStatus status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
