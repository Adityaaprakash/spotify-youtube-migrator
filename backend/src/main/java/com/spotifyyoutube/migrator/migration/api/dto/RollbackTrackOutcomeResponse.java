package com.spotifyyoutube.migrator.migration.api.dto;

/**
 * Itemized outcome response for an individual track's rollback operation (Phase 11F).
 *
 * @param targetPosition 0-indexed destination position
 * @param destinationTrackId destination video identifier
 * @param destinationPlaylistItemId destination playlist item identifier
 * @param status status ("ROLLED_BACK", "ALREADY_ROLLED_BACK", "FAILED")
 * @param message diagnostic message or error description
 */
public record RollbackTrackOutcomeResponse(
        int targetPosition,
        String destinationTrackId,
        String destinationPlaylistItemId,
        String status,
        String message
) {
}
