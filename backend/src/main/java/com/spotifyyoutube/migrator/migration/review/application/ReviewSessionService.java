package com.spotifyyoutube.migrator.migration.review.application;

import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlan;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewProgressSummary;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;

import java.util.List;
import java.util.UUID;

/**
 * Application service managing playlist migration review sessions and human decisions.
 */
public interface ReviewSessionService {

    /**
     * Creates a new review session from an existing migration plan snapshot.
     * Initial track states are mapped according to the plan's classifications.
     *
     * @param userId ID of the authenticated user owning the session
     * @param plan transient MigrationPlan snapshot (Phase 9)
     * @return newly persisted ReviewSession in IN_PROGRESS status
     */
    ReviewSession createSessionFromPlan(UUID userId, MigrationPlan plan);

    /**
     * Retrieves a review session by its unique ID.
     *
     * @param sessionId review session ID
     * @return ReviewSession
     */
    ReviewSession getSession(UUID sessionId);

    /**
     * Retrieves a review session by ID, verifying that it belongs to the given user.
     *
     * @param sessionId review session ID
     * @param userId user ID
     * @return ReviewSession
     */
    ReviewSession getSessionForUser(UUID sessionId, UUID userId);

    /**
     * Lists all review sessions for a user, ordered newest first.
     *
     * @param userId user ID
     * @return list of review sessions
     */
    List<ReviewSession> getUserSessions(UUID userId);

    /**
     * Computes the current real-time review progress summary for a session.
     *
     * @param sessionId review session ID
     * @return reconciled ReviewProgressSummary
     */
    ReviewProgressSummary getReviewProgress(UUID sessionId);

    /**
     * Records a manual candidate selection for a source track at the given position.
     *
     * @param sessionId review session ID
     * @param position 0-indexed track position in the playlist
     * @param candidate selected destination candidate
     * @param notes optional decision notes
     * @return updated ReviewTrack
     */
    ReviewTrack selectCandidate(UUID sessionId, int position, DestinationTrackCandidate candidate, String notes);

    /**
     * Accepts the original automatic matching candidate for a track at the given position.
     *
     * @param sessionId review session ID
     * @param position 0-indexed track position in the playlist
     * @return updated ReviewTrack
     */
    ReviewTrack acceptAutomaticMatch(UUID sessionId, int position);

    /**
     * Explicitly excludes a track from migration.
     *
     * @param sessionId review session ID
     * @param position 0-indexed track position in the playlist
     * @param reason optional exclusion reason
     * @return updated ReviewTrack
     */
    ReviewTrack excludeTrack(UUID sessionId, int position, String reason);

    /**
     * Completes and finalizes a review session if all required decisions and conflicts are resolved.
     *
     * @param sessionId review session ID
     * @return finalized ReviewSession in COMPLETED status
     */
    ReviewSession completeSession(UUID sessionId);

    /**
     * Cancels an in-progress review session.
     *
     * @param sessionId review session ID
     * @return cancelled ReviewSession in CANCELLED status
     */
    ReviewSession cancelSession(UUID sessionId);

    /**
     * Searches for alternative destination candidates for a track in an in-progress review session
     * using the track's normalized metadata and deterministic query formulation.
     *
     * @param sessionId review session ID
     * @param position 0-indexed track position in the playlist
     * @return bounded, deduplicated search result
     */
    ReviewCandidateSearchResult searchCandidates(UUID sessionId, int position);

    /**
     * Searches for alternative destination candidates for a track in an in-progress review session
     * with custom search parameters.
     *
     * @param sessionId review session ID
     * @param position 0-indexed track position in the playlist
     * @param request search parameters
     * @return bounded, deduplicated search result
     */
    ReviewCandidateSearchResult searchCandidates(UUID sessionId, int position, ReviewCandidateSearchRequest request);

    /**
     * Searches for alternative destination candidates for a track in an in-progress review session
     * with custom search parameters and user ownership validation.
     *
     * @param sessionId review session ID
     * @param position 0-indexed track position in the playlist
     * @param request search parameters
     * @param userId user ID owning the review session
     * @return bounded, deduplicated search result
     */
    ReviewCandidateSearchResult searchCandidates(UUID sessionId, int position, ReviewCandidateSearchRequest request, UUID userId);

    /**
     * Retrieves all review tracks for a session in strict position order (0-indexed).
     *
     * @param sessionId review session ID
     * @return ordered list of ReviewTracks
     */
    List<ReviewTrack> getTracks(UUID sessionId);

    /**
     * Retrieves all review tracks for a session in strict position order, verifying user ownership.
     *
     * @param sessionId review session ID
     * @param userId user ID owning the review session
     * @return ordered list of ReviewTracks
     */
    List<ReviewTrack> getTracksForUser(UUID sessionId, UUID userId);

    /**
     * Retrieves a single review track by session ID and track position.
     *
     * @param sessionId review session ID
     * @param position 0-indexed track position in the playlist
     * @return ReviewTrack
     */
    ReviewTrack getTrack(UUID sessionId, int position);

    /**
     * Retrieves a single review track by session ID and track position, verifying user ownership.
     *
     * @param sessionId review session ID
     * @param position 0-indexed track position in the playlist
     * @param userId user ID owning the review session
     * @return ReviewTrack
     */
    ReviewTrack getTrackForUser(UUID sessionId, int position, UUID userId);

    /**
     * Records a manual candidate selection for a track, verifying user ownership of the session.
     *
     * @param sessionId review session ID
     * @param position 0-indexed track position in the playlist
     * @param candidate selected destination candidate
     * @param notes optional decision notes
     * @param userId user ID owning the review session
     * @return updated ReviewTrack
     */
    ReviewTrack selectCandidate(UUID sessionId, int position, DestinationTrackCandidate candidate, String notes, UUID userId);

    /**
     * Accepts the original automatic matching candidate for a track, verifying user ownership.
     *
     * @param sessionId review session ID
     * @param position 0-indexed track position in the playlist
     * @param userId user ID owning the review session
     * @return updated ReviewTrack
     */
    ReviewTrack acceptAutomaticMatch(UUID sessionId, int position, UUID userId);

    /**
     * Explicitly excludes a track from migration, verifying user ownership of the session.
     *
     * @param sessionId review session ID
     * @param position 0-indexed track position in the playlist
     * @param reason optional exclusion reason
     * @param userId user ID owning the review session
     * @return updated ReviewTrack
     */
    ReviewTrack excludeTrack(UUID sessionId, int position, String reason, UUID userId);

    /**
     * Completes and finalizes a review session, verifying user ownership of the session.
     *
     * @param sessionId review session ID
     * @param userId user ID owning the review session
     * @return finalized ReviewSession in COMPLETED status
     */
    ReviewSession completeSession(UUID sessionId, UUID userId);

    /**
     * Cancels an in-progress review session, verifying user ownership of the session.
     *
     * @param sessionId review session ID
     * @param userId user ID owning the review session
     * @return cancelled ReviewSession in CANCELLED status
     */
    ReviewSession cancelSession(UUID sessionId, UUID userId);

    /**
     * Computes the current real-time review progress summary for a session, verifying user ownership.
     *
     * @param sessionId review session ID
     * @param userId user ID owning the review session
     * @return reconciled ReviewProgressSummary
     */
    ReviewProgressSummary getReviewProgressForUser(UUID sessionId, UUID userId);
}

