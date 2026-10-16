package com.spotifyyoutube.migrator.migration.review.domain;

import com.spotifyyoutube.migrator.common.domain.BaseEntity;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import com.spotifyyoutube.migrator.migration.application.plan.PlanTrackClassification;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Persisted review domain entity representing a single track's review state within a {@link ReviewSession}.
 * <p>
 * Strictly preserves the original automatic matching evidence (confidence score, calibrated probability,
 * classification, candidate details, reasons) without modification, while tracking subsequent human review decisions,
 * manual candidate replacements, exclusions, and conflict states.
 */
@Entity
@Table(name = "review_tracks")
public class ReviewTrack extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ReviewSession session;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "source_track_id", nullable = false)
    private String sourceTrackId;

    @Column(name = "source_title", nullable = false, length = 500)
    private String sourceTitle;

    @Column(name = "source_artist", nullable = false, length = 500)
    private String sourceArtist;

    // --- Original Automatic Matching Evidence (Immutable Provenance) ---

    @Enumerated(EnumType.STRING)
    @Column(name = "original_classification", nullable = false)
    private PlanTrackClassification originalClassification;

    @Column(name = "original_candidate_id")
    private String originalCandidateId;

    @Column(name = "original_candidate_title", length = 500)
    private String originalCandidateTitle;

    @Column(name = "original_candidate_channel", length = 500)
    private String originalCandidateChannel;

    @Column(name = "original_candidate_duration_ms")
    private Long originalCandidateDurationMs;

    @Column(name = "original_match_score")
    private Double originalMatchScore;

    @Column(name = "original_calibrated_probability")
    private Double originalCalibratedProbability;

    @Enumerated(EnumType.STRING)
    @Column(name = "original_match_decision")
    private MatchDecision originalMatchDecision;

    @Column(name = "original_match_reasons", columnDefinition = "TEXT")
    private String originalMatchReasons;

    // --- Review Decision State (Mutable via Domain Methods) ---

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    private ReviewTrackStatus reviewStatus = ReviewTrackStatus.AWAITING_REVIEW;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_source", nullable = false)
    private ReviewDecisionSource decisionSource = ReviewDecisionSource.AUTOMATIC;

    @Column(name = "selected_candidate_id")
    private String selectedCandidateId;

    @Column(name = "selected_candidate_title", length = 500)
    private String selectedCandidateTitle;

    @Column(name = "selected_candidate_channel", length = 500)
    private String selectedCandidateChannel;

    @Column(name = "selected_candidate_duration_ms")
    private Long selectedCandidateDurationMs;

    @Column(name = "is_excluded", nullable = false)
    private boolean excluded = false;

    @Column(name = "has_conflict", nullable = false)
    private boolean hasConflict = false;

    @Column(name = "conflict_reason", length = 1000)
    private String conflictReason;

    @Column(name = "decision_notes", columnDefinition = "TEXT")
    private String decisionNotes;

    public ReviewTrack() {
    }

    /**
     * Accepts the original automatic candidate for this track.
     * Idempotent if already accepted with the identical candidate.
     */
    public void acceptOriginalMatch() {
        checkSessionModifiable();

        if (originalCandidateId == null || originalCandidateId.isBlank()) {
            throw new InvalidStateException("Cannot accept automatic match for track at position "
                    + position + " (" + sourceTitle + ") because no automatic candidate exists");
        }

        if (this.reviewStatus == ReviewTrackStatus.ACCEPTED
                && Objects.equals(this.selectedCandidateId, this.originalCandidateId)
                && !this.excluded) {
            return; // Idempotent no-op
        }

        this.selectedCandidateId = this.originalCandidateId;
        this.selectedCandidateTitle = this.originalCandidateTitle;
        this.selectedCandidateChannel = this.originalCandidateChannel;
        this.selectedCandidateDurationMs = this.originalCandidateDurationMs;
        this.reviewStatus = ReviewTrackStatus.ACCEPTED;
        this.decisionSource = (originalClassification == PlanTrackClassification.AUTO_MATCH)
                ? ReviewDecisionSource.AUTOMATIC
                : ReviewDecisionSource.USER_SELECTED;
        this.excluded = false;
        this.hasConflict = false;
        this.conflictReason = null;
    }

    /**
     * Manually selects a replacement candidate chosen by the user.
     * Preserves original matching evidence untouched and records USER_SELECTED provenance.
     * Idempotent if already selected with the identical candidate ID.
     *
     * @param candidate candidate chosen by the user
     * @param notes optional decision notes or explanation
     */
    public void selectCandidate(DestinationTrackCandidate candidate, String notes) {
        checkSessionModifiable();

        Objects.requireNonNull(candidate, "Candidate must not be null");
        if (candidate.id() == null || candidate.id().isBlank()) {
            throw new IllegalArgumentException("Candidate ID must not be null or blank");
        }
        if (candidate.title() == null || candidate.title().isBlank()) {
            throw new IllegalArgumentException("Candidate title must not be null or blank");
        }
        if (candidate.duration() != null && candidate.duration().isNegative()) {
            throw new IllegalArgumentException("Candidate duration cannot be negative: " + candidate.duration());
        }

        String candidateId = candidate.id().trim();
        if (this.reviewStatus == ReviewTrackStatus.USER_SELECTED
                && Objects.equals(this.selectedCandidateId, candidateId)
                && !this.excluded) {
            return; // Idempotent no-op
        }

        this.selectedCandidateId = candidateId;
        this.selectedCandidateTitle = candidate.title().trim();
        this.selectedCandidateChannel = candidate.channelOrArtist();
        this.selectedCandidateDurationMs = candidate.duration() != null ? candidate.duration().toMillis() : null;
        this.reviewStatus = ReviewTrackStatus.USER_SELECTED;
        this.decisionSource = ReviewDecisionSource.USER_SELECTED;
        this.excluded = false;
        this.hasConflict = false;
        this.conflictReason = null;
        this.decisionNotes = notes;
    }

    /**
     * Explicitly excludes this track from migration.
     * An excluded track cannot be treated as approved for migration (Invariant 3).
     * Idempotent if already excluded.
     *
     * @param reason optional reason for exclusion
     */
    public void exclude(String reason) {
        checkSessionModifiable();

        if (this.reviewStatus == ReviewTrackStatus.EXCLUDED && this.excluded) {
            return; // Idempotent no-op
        }

        this.excluded = true;
        this.reviewStatus = ReviewTrackStatus.EXCLUDED;
        this.decisionSource = ReviewDecisionSource.USER_SELECTED;
        this.selectedCandidateId = null;
        this.selectedCandidateTitle = null;
        this.selectedCandidateChannel = null;
        this.selectedCandidateDurationMs = null;
        this.hasConflict = false;
        this.conflictReason = null;
        this.decisionNotes = reason;
    }

    /**
     * Flags a conflict on this track (e.g. duplicate destination video ID).
     */
    public void setConflict(String reason) {
        this.hasConflict = true;
        this.conflictReason = reason;
    }

    /**
     * Clears any active conflict on this track.
     */
    public void clearConflict() {
        this.hasConflict = false;
        this.conflictReason = null;
    }

    /**
     * Determines whether this track is approved and ready for migration.
     * Invariant 3: Excluded tracks are NEVER approved for migration.
     * Invariant 4: Conflicted tracks are NOT approved for migration.
     */
    public boolean isApprovedForMigration() {
        return !excluded
                && (reviewStatus == ReviewTrackStatus.ACCEPTED || reviewStatus == ReviewTrackStatus.USER_SELECTED)
                && selectedCandidateId != null
                && !selectedCandidateId.isBlank()
                && !hasConflict;
    }

    /**
     * Determines whether this track has an unresolved required decision.
     */
    public boolean isUnresolved() {
        return reviewStatus == ReviewTrackStatus.AWAITING_REVIEW || (hasConflict && !excluded);
    }

    /**
     * Reconstructs a {@link DestinationTrackCandidate} representation of the currently selected candidate, if any.
     */
    public Optional<DestinationTrackCandidate> getSelectedCandidate() {
        if (selectedCandidateId == null || selectedCandidateId.isBlank()) {
            return Optional.empty();
        }
        Duration duration = selectedCandidateDurationMs != null ? Duration.ofMillis(selectedCandidateDurationMs) : null;
        return Optional.of(new DestinationTrackCandidate(
                selectedCandidateId,
                selectedCandidateTitle,
                selectedCandidateChannel,
                null,
                null,
                null,
                duration
        ));
    }

    /**
     * Reconstructs a {@link DestinationTrackCandidate} representation of the original automatic candidate, if any.
     */
    public Optional<DestinationTrackCandidate> getOriginalCandidate() {
        if (originalCandidateId == null || originalCandidateId.isBlank()) {
            return Optional.empty();
        }
        Duration duration = originalCandidateDurationMs != null ? Duration.ofMillis(originalCandidateDurationMs) : null;
        return Optional.of(new DestinationTrackCandidate(
                originalCandidateId,
                originalCandidateTitle,
                originalCandidateChannel,
                null,
                null,
                null,
                duration
        ));
    }

    private void checkSessionModifiable() {
        if (session != null && !session.isInProgress()) {
            throw new InvalidStateException("Cannot modify review track because session is in status: "
                    + session.getStatus());
        }
    }

    // --- Standard Getters and Setters ---

    public ReviewSession getSession() {
        return session;
    }

    public void setSession(ReviewSession session) {
        this.session = session;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getSourceTrackId() {
        return sourceTrackId;
    }

    public void setSourceTrackId(String sourceTrackId) {
        this.sourceTrackId = sourceTrackId;
    }

    public String getSourceTitle() {
        return sourceTitle;
    }

    public void setSourceTitle(String sourceTitle) {
        this.sourceTitle = sourceTitle;
    }

    public String getSourceArtist() {
        return sourceArtist;
    }

    public void setSourceArtist(String sourceArtist) {
        this.sourceArtist = sourceArtist;
    }

    public PlanTrackClassification getOriginalClassification() {
        return originalClassification;
    }

    public void setOriginalClassification(PlanTrackClassification originalClassification) {
        this.originalClassification = originalClassification;
    }

    public String getOriginalCandidateId() {
        return originalCandidateId;
    }

    public void setOriginalCandidateId(String originalCandidateId) {
        this.originalCandidateId = originalCandidateId;
    }

    public String getOriginalCandidateTitle() {
        return originalCandidateTitle;
    }

    public void setOriginalCandidateTitle(String originalCandidateTitle) {
        this.originalCandidateTitle = originalCandidateTitle;
    }

    public String getOriginalCandidateChannel() {
        return originalCandidateChannel;
    }

    public void setOriginalCandidateChannel(String originalCandidateChannel) {
        this.originalCandidateChannel = originalCandidateChannel;
    }

    public Long getOriginalCandidateDurationMs() {
        return originalCandidateDurationMs;
    }

    public void setOriginalCandidateDurationMs(Long originalCandidateDurationMs) {
        this.originalCandidateDurationMs = originalCandidateDurationMs;
    }

    public Double getOriginalMatchScore() {
        return originalMatchScore;
    }

    public void setOriginalMatchScore(Double originalMatchScore) {
        this.originalMatchScore = originalMatchScore;
    }

    public Double getOriginalCalibratedProbability() {
        return originalCalibratedProbability;
    }

    public void setOriginalCalibratedProbability(Double originalCalibratedProbability) {
        this.originalCalibratedProbability = originalCalibratedProbability;
    }

    public MatchDecision getOriginalMatchDecision() {
        return originalMatchDecision;
    }

    public void setOriginalMatchDecision(MatchDecision originalMatchDecision) {
        this.originalMatchDecision = originalMatchDecision;
    }

    public String getOriginalMatchReasons() {
        return originalMatchReasons;
    }

    public void setOriginalMatchReasons(String originalMatchReasons) {
        this.originalMatchReasons = originalMatchReasons;
    }

    public ReviewTrackStatus getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(ReviewTrackStatus reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public ReviewDecisionSource getDecisionSource() {
        return decisionSource;
    }

    public void setDecisionSource(ReviewDecisionSource decisionSource) {
        this.decisionSource = decisionSource;
    }

    public String getSelectedCandidateId() {
        return selectedCandidateId;
    }

    public void setSelectedCandidateId(String selectedCandidateId) {
        this.selectedCandidateId = selectedCandidateId;
    }

    public String getSelectedCandidateTitle() {
        return selectedCandidateTitle;
    }

    public void setSelectedCandidateTitle(String selectedCandidateTitle) {
        this.selectedCandidateTitle = selectedCandidateTitle;
    }

    public String getSelectedCandidateChannel() {
        return selectedCandidateChannel;
    }

    public void setSelectedCandidateChannel(String selectedCandidateChannel) {
        this.selectedCandidateChannel = selectedCandidateChannel;
    }

    public Long getSelectedCandidateDurationMs() {
        return selectedCandidateDurationMs;
    }

    public void setSelectedCandidateDurationMs(Long selectedCandidateDurationMs) {
        this.selectedCandidateDurationMs = selectedCandidateDurationMs;
    }

    public boolean isExcluded() {
        return excluded;
    }

    public void setExcluded(boolean excluded) {
        this.excluded = excluded;
    }

    public boolean hasConflict() {
        return hasConflict;
    }

    public void setHasConflict(boolean hasConflict) {
        this.hasConflict = hasConflict;
    }

    public String getConflictReason() {
        return conflictReason;
    }

    public void setConflictReason(String conflictReason) {
        this.conflictReason = conflictReason;
    }

    public String getDecisionNotes() {
        return decisionNotes;
    }

    public void setDecisionNotes(String decisionNotes) {
        this.decisionNotes = decisionNotes;
    }
}
