package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.migration.application.execution.contract.InsertTracksExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.PlaylistTrackInsertionSummary;

import java.util.UUID;

/**
 * Application service coordinating ordered track insertion into a destination playlist (Phase 11D).
 * <p>
 * Inserts approved destination tracks in strict canonical target position sequence, records per-track
 * outcomes, enforces failure-continuation policies, and produces comprehensive execution summaries.
 */
public interface OrderedTrackInsertionService {

    /**
     * Validates the execution context and destination playlist, then sequentially inserts approved tracks.
     *
     * @param request execution parameters including review session ID, destination playlist ID, and optional user ID
     * @param authenticatedUserId ID of the authenticated user possessing destination authorization
     * @return comprehensive PlaylistTrackInsertionSummary
     */
    PlaylistTrackInsertionSummary insertApprovedTracks(
            InsertTracksExecutionRequest request,
            UUID authenticatedUserId
    );

    /**
     * Convenience overload to insert approved tracks by session ID and destination playlist ID.
     *
     * @param reviewSessionId unique review session identifier
     * @param destinationPlaylistId platform identifier of the created destination playlist
     * @param authenticatedUserId ID of the authenticated user
     * @return comprehensive PlaylistTrackInsertionSummary
     */
    PlaylistTrackInsertionSummary insertApprovedTracks(
            UUID reviewSessionId,
            String destinationPlaylistId,
            UUID authenticatedUserId
    );
}
