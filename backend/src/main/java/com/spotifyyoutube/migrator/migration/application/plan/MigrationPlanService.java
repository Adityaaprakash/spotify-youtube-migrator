package com.spotifyyoutube.migrator.migration.application.plan;

import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanPreviewRequest;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanResponse;

import java.util.UUID;

/**
 * Application service interface for generating and managing migration plan previews.
 * <p>
 * Coordinates with {@link PlaylistMigrationPlanGenerator} to produce immutable, transient
 * migration previews without performing any write or mutation operations on the target platform.
 */
public interface MigrationPlanService {

    /**
     * Generates a migration plan preview response for an authenticated user's source playlist.
     *
     * @param userId authenticated user ID
     * @param request preview request containing playlist identifier and optional deduplication flag
     * @return structured preview response
     */
    MigrationPlanResponse generatePreview(UUID userId, MigrationPlanPreviewRequest request);

    /**
     * Generates an immutable migration plan domain contract directly from identifiers.
     *
     * @param userId authenticated user ID
     * @param sourcePlaylistId source platform playlist identifier
     * @param deduplicate whether to detect and flag duplicates
     * @return immutable point-in-time MigrationPlan
     */
    MigrationPlan generatePlan(UUID userId, String sourcePlaylistId, boolean deduplicate);
}
