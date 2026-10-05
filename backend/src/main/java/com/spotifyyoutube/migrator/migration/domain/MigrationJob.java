package com.spotifyyoutube.migrator.migration.domain;

import com.spotifyyoutube.migrator.common.domain.BaseEntity;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "migration_jobs")
public class MigrationJob extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "source_playlist_id", nullable = false)
    private String sourcePlaylistId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_platform", nullable = false)
    private Platform sourcePlatform;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_platform", nullable = false)
    private Platform targetPlatform;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MigrationStatus status = MigrationStatus.PENDING;

    @Column(name = "total_tracks")
    private Integer totalTracks;

    @Column(name = "processed_tracks")
    private Integer processedTracks = 0;

    @Column(name = "failed_tracks")
    private Integer failedTracks = 0;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;
    
    @Column(name = "target_playlist_url")
    private String targetPlaylistUrl;

    public MigrationJob() {
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getSourcePlaylistId() {
        return sourcePlaylistId;
    }

    public void setSourcePlaylistId(String sourcePlaylistId) {
        this.sourcePlaylistId = sourcePlaylistId;
    }

    public Platform getSourcePlatform() {
        return sourcePlatform;
    }

    public void setSourcePlatform(Platform sourcePlatform) {
        this.sourcePlatform = sourcePlatform;
    }

    public Platform getTargetPlatform() {
        return targetPlatform;
    }

    public void setTargetPlatform(Platform targetPlatform) {
        this.targetPlatform = targetPlatform;
    }

    public MigrationStatus getStatus() {
        return status;
    }

    public void setStatus(MigrationStatus status) {
        this.status = status;
    }

    public Integer getTotalTracks() {
        return totalTracks;
    }

    public void setTotalTracks(Integer totalTracks) {
        this.totalTracks = totalTracks;
    }

    public Integer getProcessedTracks() {
        return processedTracks;
    }

    public void setProcessedTracks(Integer processedTracks) {
        this.processedTracks = processedTracks;
    }

    public Integer getFailedTracks() {
        return failedTracks;
    }

    public void setFailedTracks(Integer failedTracks) {
        this.failedTracks = failedTracks;
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

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getTargetPlaylistUrl() {
        return targetPlaylistUrl;
    }

    public void setTargetPlaylistUrl(String targetPlaylistUrl) {
        this.targetPlaylistUrl = targetPlaylistUrl;
    }
}
