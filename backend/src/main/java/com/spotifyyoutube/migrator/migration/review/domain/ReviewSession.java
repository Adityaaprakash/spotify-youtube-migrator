package com.spotifyyoutube.migrator.migration.review.domain;

import com.spotifyyoutube.migrator.common.domain.BaseEntity;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.identity.domain.User;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persisted review domain entity representing a human review session for a playlist migration.
 * <p>
 * Associated with a specific user, source playlist, and pre-flight {@link com.spotifyyoutube.migrator.migration.application.plan.MigrationPlan}.
 * Governs the review session lifecycle (IN_PROGRESS, COMPLETED, CANCELLED) and enforces invariants
 * preventing premature completion while unresolved track decisions or conflicts remain.
 */
@Entity
@Table(name = "review_sessions")
public class ReviewSession extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "source_playlist_id", nullable = false)
    private String sourcePlaylistId;

    @Column(name = "source_playlist_title", nullable = false, length = 500)
    private String sourcePlaylistTitle;

    @Column(name = "plan_id")
    private UUID planId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReviewSessionStatus status = ReviewSessionStatus.IN_PROGRESS;

    @Column(name = "total_tracks", nullable = false)
    private Integer totalTracks = 0;

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<ReviewTrack> tracks = new ArrayList<>();

    public ReviewSession() {
    }

    public ReviewSession(User user, String sourcePlaylistId, String sourcePlaylistTitle, UUID planId) {
        this.user = Objects.requireNonNull(user, "User must not be null");
        this.sourcePlaylistId = Objects.requireNonNull(sourcePlaylistId, "sourcePlaylistId must not be null");
        this.sourcePlaylistTitle = sourcePlaylistTitle != null && !sourcePlaylistTitle.isBlank()
                ? sourcePlaylistTitle
                : "Migrated Playlist";
        this.planId = planId;
        this.status = ReviewSessionStatus.IN_PROGRESS;
        this.totalTracks = 0;
    }

    /**
     * Completes and finalizes this review session.
     * Enforces Invariant 4 & Invariant 5: Cannot complete while required decisions or conflicts remain unresolved.
     *
     * @param summary current reconciled ReviewProgressSummary
     */
    public void complete(ReviewProgressSummary summary) {
        Objects.requireNonNull(summary, "ReviewProgressSummary must not be null");

        if (!isInProgress()) {
            throw new InvalidStateException("Cannot complete review session in status: " + status);
        }

        if (!summary.eligibleForCompletion()) {
            throw new InvalidStateException(String.format(
                    "Cannot complete review session %s: %d required decisions remain unresolved (%d awaiting, %d conflicts)",
                    getId(), summary.remainingRequiredDecisions(), summary.awaitingReviewCount(), summary.unresolvedConflictCount()
            ));
        }

        this.status = ReviewSessionStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    /**
     * Cancels or abandons this review session.
     */
    public void cancel() {
        if (!isInProgress()) {
            throw new InvalidStateException("Cannot cancel review session in status: " + status);
        }
        this.status = ReviewSessionStatus.CANCELLED;
    }

    /**
     * Derives an aggregate review progress summary directly from current review tracks.
     */
    public ReviewProgressSummary deriveSummary() {
        return ReviewProgressSummary.fromTracks(this.tracks);
    }

    /**
     * Adds a review track to this session, maintaining bidirectional relationship and total count.
     */
    public void addTrack(ReviewTrack track) {
        Objects.requireNonNull(track, "ReviewTrack must not be null");
        this.tracks.add(track);
        track.setSession(this);
        this.totalTracks = this.tracks.size();
    }

    public boolean isInProgress() {
        return status == ReviewSessionStatus.IN_PROGRESS;
    }

    public boolean isCompleted() {
        return status == ReviewSessionStatus.COMPLETED;
    }

    public boolean isCancelled() {
        return status == ReviewSessionStatus.CANCELLED;
    }

    // --- Standard Getters and Setters ---

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

    public String getSourcePlaylistTitle() {
        return sourcePlaylistTitle;
    }

    public void setSourcePlaylistTitle(String sourcePlaylistTitle) {
        this.sourcePlaylistTitle = sourcePlaylistTitle;
    }

    public UUID getPlanId() {
        return planId;
    }

    public void setPlanId(UUID planId) {
        this.planId = planId;
    }

    public ReviewSessionStatus getStatus() {
        return status;
    }

    public void setStatus(ReviewSessionStatus status) {
        this.status = status;
    }

    public Integer getTotalTracks() {
        return totalTracks;
    }

    public void setTotalTracks(Integer totalTracks) {
        this.totalTracks = totalTracks;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public List<ReviewTrack> getTracks() {
        return tracks;
    }

    public void setTracks(List<ReviewTrack> tracks) {
        this.tracks = tracks != null ? tracks : new ArrayList<>();
        this.totalTracks = this.tracks.size();
    }
}
