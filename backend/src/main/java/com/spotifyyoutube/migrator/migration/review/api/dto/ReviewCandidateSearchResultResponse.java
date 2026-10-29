package com.spotifyyoutube.migrator.migration.review.api.dto;

import com.spotifyyoutube.migrator.migration.api.dto.DestinationCandidateResponse;
import com.spotifyyoutube.migrator.migration.review.application.ReviewCandidateSearchResult;

import java.util.List;
import java.util.UUID;

/**
 * API response representing the candidate discovery search results for a review track.
 *
 * @param sessionId review session identifier
 * @param trackPosition 0-indexed position of the track in the playlist
 * @param sourceTrackId source provider track identifier (e.g. Spotify external ID)
 * @param sourceTitle source track title
 * @param sourceArtist source track artist credit
 * @param queryUsed search query actually executed
 * @param candidates discovered candidate tracks
 * @param isCustomQuery whether the search used an explicit reviewer query
 * @param totalResults number of returned candidates
 */
public record ReviewCandidateSearchResultResponse(
        UUID sessionId,
        int trackPosition,
        String sourceTrackId,
        String sourceTitle,
        String sourceArtist,
        String queryUsed,
        List<DestinationCandidateResponse> candidates,
        boolean isCustomQuery,
        int totalResults
) {

    public static ReviewCandidateSearchResultResponse from(ReviewCandidateSearchResult result) {
        if (result == null) {
            return null;
        }
        List<DestinationCandidateResponse> candidateResponses = result.candidates() != null
                ? result.candidates().stream().map(DestinationCandidateResponse::from).toList()
                : List.of();

        return new ReviewCandidateSearchResultResponse(
                result.sessionId(),
                result.trackPosition(),
                result.sourceTrackId(),
                result.sourceTitle(),
                result.sourceArtist(),
                result.queryUsed(),
                candidateResponses,
                result.isCustomQuery(),
                result.totalResults()
        );
    }
}
