package com.spotifyyoutube.migrator.migration.api.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request payload to initiate partial or complete rollback of inserted destination tracks (Phase 11F).
 *
 * @param targetPositions optional list of canonical target positions to roll back; if empty/null, all inserted tracks are rolled back
 * @param userId optional caller-supplied user ID for anti-impersonation verification
 */
public record RollbackExecutionApiRequest(
        @Size(max = 1000, message = "targetPositions list cannot exceed 1000 items")
        List<Integer> targetPositions,
        UUID userId
) {
}
