package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.DestinationPlaylistCreationResult;

import java.util.UUID;

/**
 * Application service coordinating destination playlist creation (Phase 11C).
 * <p>
 * Creates the destination playlist using the server-side validated {@link com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext}.
 * Strictly creates the destination playlist only and performs ZERO track insertions.
 */
public interface DestinationPlaylistCreationService {

    /**
     * Validates the execution context and creates the destination playlist.
     * Strictly creates the playlist only without inserting tracks.
     *
     * @param request execution parameters including review session ID and optional overrides
     * @param authenticatedUserId ID of the authenticated user
     * @return typed DestinationPlaylistCreationResult with confirmed destination playlist identity
     */
    DestinationPlaylistCreationResult createDestinationPlaylist(
            ApprovedMigrationExecutionRequest request,
            UUID authenticatedUserId
    );

    /**
     * Validates the review session and creates the destination playlist by session ID.
     * Strictly creates the playlist only without inserting tracks.
     *
     * @param reviewSessionId unique review session identifier
     * @param authenticatedUserId ID of the authenticated user
     * @return typed DestinationPlaylistCreationResult with confirmed destination playlist identity
     */
    DestinationPlaylistCreationResult createDestinationPlaylist(
            UUID reviewSessionId,
            UUID authenticatedUserId
    );
}
