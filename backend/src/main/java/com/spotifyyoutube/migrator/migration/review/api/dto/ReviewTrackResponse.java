package com.spotifyyoutube.migrator.migration.review.api.dto;

import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.application.plan.PlanTrackClassification;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrackStatus;

import java.util.UUID;

/**
 * Itemized API representation of a source track and its review state within a review session.
 * <p>
 * Strictly isolates immutable automatic matching evidence from mutable human decisions.
 */
public record ReviewTrackResponse(
        UUID id,
        UUID sessionId,
        int position,
        String sourceTrackId,
        String sourceTitle,
        String sourceArtist,

        // Automatic matching evidence (immutable provenance)
        PlanTrackClassification originalClassification,
        ReviewCandidateResponse originalCandidate,
        Double originalMatchScore,
        Double originalCalibratedProbability,
        MatchDecision originalMatchDecision,
        String originalMatchReasons,

        // Review decision state
        ReviewTrackStatus reviewStatus,
        ReviewDecisionSource decisionSource,
        ReviewCandidateResponse selectedCandidate,
        boolean excluded,
        boolean hasConflict,
        String conflictReason,
        String decisionNotes,

        // Derived flags
        boolean approvedForMigration,
        boolean unresolved
) {

    public static ReviewTrackResponse from(ReviewTrack track) {
        if (track == null) {
            return null;
        }

        ReviewCandidateResponse originalCandidate = track.getOriginalCandidateId() != null
                ? new ReviewCandidateResponse(
                        track.getOriginalCandidateId(),
                        track.getOriginalCandidateTitle(),
                        track.getOriginalCandidateChannel(),
                        track.getOriginalCandidateDurationMs()
                )
                : null;

        ReviewCandidateResponse selectedCandidate = track.getSelectedCandidateId() != null
                ? new ReviewCandidateResponse(
                        track.getSelectedCandidateId(),
                        track.getSelectedCandidateTitle(),
                        track.getSelectedCandidateChannel(),
                        track.getSelectedCandidateDurationMs()
                )
                : null;

        UUID sessionId = track.getSession() != null ? track.getSession().getId() : null;

        return new ReviewTrackResponse(
                track.getId(),
                sessionId,
                track.getPosition(),
                track.getSourceTrackId(),
                track.getSourceTitle(),
                track.getSourceArtist(),
                track.getOriginalClassification(),
                originalCandidate,
                track.getOriginalMatchScore(),
                track.getOriginalCalibratedProbability(),
                track.getOriginalMatchDecision(),
                track.getOriginalMatchReasons(),
                track.getReviewStatus(),
                track.getDecisionSource(),
                selectedCandidate,
                track.isExcluded(),
                track.hasConflict(),
                track.getConflictReason(),
                track.getDecisionNotes(),
                track.isApprovedForMigration(),
                track.isUnresolved()
        );
    }
}
