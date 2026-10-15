package com.spotifyyoutube.migrator.migration.application.plan;

import java.util.UUID;

/**
 * Application service interface for generating immutable migration plans for source playlists.
 * <p>
 * Evaluates candidate discovery and matching without performing any write or mutation operations
 * on the destination platform.
 */
public interface PlaylistMigrationPlanGenerator {

    /**
     * Generates a deterministic preview migration plan for the specified source playlist.
     *
     * @param userId authenticated user ID
     * @param sourcePlaylistId source provider playlist identifier (e.g. Spotify playlist ID)
     * @param deduplicate whether to detect and flag duplicate destination tracks
     * @return immutable point-in-time MigrationPlan
     */
    MigrationPlan generatePlan(UUID userId, String sourcePlaylistId, boolean deduplicate);

    /**
     * Generates a preview migration plan with destination candidate deduplication enabled by default.
     *
     * @param userId authenticated user ID
     * @param sourcePlaylistId source provider playlist identifier
     * @return immutable point-in-time MigrationPlan
     */
    default MigrationPlan generatePlan(UUID userId, String sourcePlaylistId) {
        return generatePlan(userId, sourcePlaylistId, true);
    }
}
