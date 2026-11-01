package com.spotifyyoutube.migrator.migration.application.execution.contract;

import java.util.Optional;
import java.util.UUID;

/**
 * Application request record to initiate controlled recovery of a failed migration execution (Phase 11F).
 *
 * @param reviewSessionId optional review session identifier
 * @param targetPosition optional canonical target position of a specific failed track to recover
 * @param userId optional caller-supplied user ID for anti-impersonation verification
 */
public record RecoverExecutionRequest(
        UUID reviewSessionId,
        Integer targetPosition,
        UUID userId
) {

    public Optional<UUID> getReviewSessionId() {
        return Optional.ofNullable(reviewSessionId);
    }

    public Optional<Integer> getTargetPosition() {
        return Optional.ofNullable(targetPosition);
    }

    public Optional<UUID> getUserId() {
        return Optional.ofNullable(userId);
    }

    public static RecoverExecutionRequest all() {
        return new RecoverExecutionRequest(null, null, null);
    }

    public static RecoverExecutionRequest of(UUID reviewSessionId) {
        return new RecoverExecutionRequest(reviewSessionId, null, null);
    }

    public static RecoverExecutionRequest forTrack(Integer targetPosition) {
        return new RecoverExecutionRequest(null, targetPosition, null);
    }

    public static RecoverExecutionRequest forTrack(UUID reviewSessionId, Integer targetPosition) {
        return new RecoverExecutionRequest(reviewSessionId, targetPosition, null);
    }
}
