package com.spotifyyoutube.migrator.migration.review.application;

import java.util.UUID;

/**
 * Application service managing manual candidate searches and candidate pool enrichment
 * for tracks under review.
 * <p>
 * Bounded by strict external API quotas and rate limits, this service allows a reviewer
 * to search for alternative YouTube candidates without altering review session state,
 * changing decision provenance, or mutating destination playlists.
 */
public interface ReviewCandidateSearchService {

    /**
     * Searches for alternative YouTube candidates for a track in an in-progress review session
     * using the track's normalized metadata and deterministic query formulation.
     *
     * @param sessionId review session ID
     * @param position 0-indexed position of the track in the review session
     * @return bounded, deduplicated search result
     */
    ReviewCandidateSearchResult searchCandidates(UUID sessionId, int position);

    /**
     * Searches for alternative YouTube candidates for a track in an in-progress review session
     * using the parameters specified in the request (either an explicit custom query or default formulation).
     *
     * @param sessionId review session ID
     * @param position 0-indexed position of the track in the review session
     * @param request search parameters including optional custom query and limit
     * @return bounded, deduplicated search result
     */
    ReviewCandidateSearchResult searchCandidates(UUID sessionId, int position, ReviewCandidateSearchRequest request);

    /**
     * Searches for alternative YouTube candidates for a track in an in-progress review session
     * with session user ownership validation.
     *
     * @param sessionId review session ID
     * @param position 0-indexed position of the track in the review session
     * @param request search parameters including optional custom query and limit
     * @param userId expected user ID owning the review session
     * @return bounded, deduplicated search result
     */
    ReviewCandidateSearchResult searchCandidates(UUID sessionId, int position, ReviewCandidateSearchRequest request, UUID userId);
}
