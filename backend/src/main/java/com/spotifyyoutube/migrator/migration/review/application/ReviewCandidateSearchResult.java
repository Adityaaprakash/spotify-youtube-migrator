package com.spotifyyoutube.migrator.migration.review.application;

import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable outcome representation of a candidate search operation for a specific review track.
 *
 * @param sessionId unique identifier of the review session
 * @param trackPosition 0-indexed position of the track in the playlist/review session
 * @param sourceTrackId source provider track identifier (e.g. Spotify external ID)
 * @param sourceTitle source track title
 * @param sourceArtist source track artist credit
 * @param queryUsed the actual query text dispatched to the destination provider
 * @param candidates bounded, deduplicated list of discovered destination candidates
 * @param isCustomQuery whether the search used an explicit reviewer-supplied query
 * @param totalResults number of candidates discovered and returned
 */
public record ReviewCandidateSearchResult(
        UUID sessionId,
        int trackPosition,
        String sourceTrackId,
        String sourceTitle,
        String sourceArtist,
        String queryUsed,
        List<DestinationTrackCandidate> candidates,
        boolean isCustomQuery,
        int totalResults
) {

    public ReviewCandidateSearchResult {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(sourceTrackId, "sourceTrackId must not be null");
        candidates = candidates != null ? List.copyOf(candidates) : List.of();
    }

    public static ReviewCandidateSearchResult empty(
            UUID sessionId,
            int trackPosition,
            String sourceTrackId,
            String sourceTitle,
            String sourceArtist,
            String queryUsed,
            boolean isCustomQuery
    ) {
        return new ReviewCandidateSearchResult(
                sessionId,
                trackPosition,
                sourceTrackId,
                sourceTitle,
                sourceArtist,
                queryUsed,
                List.of(),
                isCustomQuery,
                0
        );
    }

    public boolean isEmpty() {
        return candidates.isEmpty();
    }

    public boolean hasCandidates() {
        return !candidates.isEmpty();
    }

    public int candidateCount() {
        return candidates.size();
    }
}
