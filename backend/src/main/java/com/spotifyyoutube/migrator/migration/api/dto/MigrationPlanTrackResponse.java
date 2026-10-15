package com.spotifyyoutube.migrator.migration.api.dto;

import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanTrack;
import com.spotifyyoutube.migrator.migration.application.plan.PlanTrackClassification;

import java.util.List;

/**
 * API response representing the planned migration outcome for a single source track.
 *
 * @param position 0-based index in the source playlist
 * @param sourceTrackId source provider track identifier
 * @param sourceTitle source track title
 * @param sourceArtist source track primary artist
 * @param classification mutually exclusive plan classification
 * @param selectedCandidate chosen destination candidate, or null if unmatched / excluded / failed
 * @param matchDecision matching engine decision, or null if uncomputed
 * @param matchScore composite similarity score in [0.0, 1.0], or null if uncomputed
 * @param calibratedProbability estimated probability of true match in [0.0, 1.0], or null if uncomputed
 * @param matchReasons decision rationale and match feature notes
 * @param errorMessage sanitized error explanation if processing failed, or null
 */
public record MigrationPlanTrackResponse(
        int position,
        String sourceTrackId,
        String sourceTitle,
        String sourceArtist,
        PlanTrackClassification classification,
        DestinationCandidateResponse selectedCandidate,
        MatchDecision matchDecision,
        Double matchScore,
        Double calibratedProbability,
        List<String> matchReasons,
        String errorMessage
) {

    public static MigrationPlanTrackResponse from(MigrationPlanTrack track) {
        if (track == null) {
            return null;
        }
        return new MigrationPlanTrackResponse(
                track.position(),
                track.sourceTrackId(),
                track.sourceTitle(),
                track.sourceArtist(),
                track.classification(),
                DestinationCandidateResponse.from(track.selectedCandidate()),
                track.matchDecision(),
                track.matchScore(),
                track.calibratedProbability(),
                track.matchReasons(),
                track.errorMessage()
        );
    }
}
