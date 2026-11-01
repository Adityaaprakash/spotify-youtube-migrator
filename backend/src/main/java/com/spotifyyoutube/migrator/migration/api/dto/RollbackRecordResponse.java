package com.spotifyyoutube.migrator.migration.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Presentation response DTO for an individual persisted rollback audit record (Phase 11F).
 */
public record RollbackRecordResponse(
        UUID id,
        UUID executionId,
        UUID trackRecordId,
        int targetPosition,
        String destinationTrackId,
        String destinationPlaylistItemId,
        String status,
        String errorMessage,
        Instant attemptedAt,
        Instant completedAt
) {
}
