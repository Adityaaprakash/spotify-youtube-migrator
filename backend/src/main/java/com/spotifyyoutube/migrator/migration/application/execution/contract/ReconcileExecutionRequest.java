package com.spotifyyoutube.migrator.migration.application.execution.contract;

import java.util.UUID;

/**
 * Domain request for reconciling an execution's uncertain tracks against remote destination state (Phase 11G).
 */
public record ReconcileExecutionRequest(
        UUID userId,
        Integer targetPosition,
        String reason
) {
    public static ReconcileExecutionRequest allTracks(UUID userId, String reason) {
        return new ReconcileExecutionRequest(userId, null, reason);
    }

    public static ReconcileExecutionRequest singleTrack(UUID userId, int targetPosition, String reason) {
        return new ReconcileExecutionRequest(userId, targetPosition, reason);
    }
}
