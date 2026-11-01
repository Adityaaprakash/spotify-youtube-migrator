package com.spotifyyoutube.migrator.migration.application.execution.contract;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application request record to initiate partial or complete rollback of inserted destination tracks (Phase 11F).
 *
 * @param targetPositions optional list of canonical target positions to roll back; if null or empty, all inserted tracks are rolled back
 * @param userId optional caller-supplied user ID for anti-impersonation verification
 */
public record RollbackExecutionRequest(
        List<Integer> targetPositions,
        UUID userId
) {

    public RollbackExecutionRequest {
        targetPositions = targetPositions != null ? List.copyOf(targetPositions) : Collections.emptyList();
    }

    public Optional<UUID> getUserId() {
        return Optional.ofNullable(userId);
    }

    public static RollbackExecutionRequest all() {
        return new RollbackExecutionRequest(Collections.emptyList(), null);
    }

    public static RollbackExecutionRequest forPositions(List<Integer> positions) {
        return new RollbackExecutionRequest(positions, null);
    }

    public static RollbackExecutionRequest forPositions(List<Integer> positions, UUID userId) {
        return new RollbackExecutionRequest(positions, userId);
    }
}
