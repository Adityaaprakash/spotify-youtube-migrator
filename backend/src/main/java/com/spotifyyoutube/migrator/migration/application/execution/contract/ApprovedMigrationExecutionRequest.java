package com.spotifyyoutube.migrator.migration.application.execution.contract;

import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlan;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable application contract representing a request to execute an approved playlist migration.
 *
 * @param reviewSessionId unique review session identifier whose decisions are to be executed
 * @param userId unique identifier of the requesting user (validated against security context)
 * @param sourcePlaylistId optional source playlist identifier for cross-verification
 * @param planId optional planning identifier for cross-verification
 * @param targetPlaylistTitle custom title for the destination playlist, or null to derive from source
 * @param targetPlaylistDescription custom description for the destination playlist
 * @param isPrivate privacy setting for the destination playlist (defaults to true if null)
 * @param plan optional transient Phase 9 MigrationPlan snapshot for deep consistency checks
 */
public record ApprovedMigrationExecutionRequest(
        UUID reviewSessionId,
        UUID userId,
        String sourcePlaylistId,
        UUID planId,
        String targetPlaylistTitle,
        String targetPlaylistDescription,
        Boolean isPrivate,
        MigrationPlan plan
) {

    public ApprovedMigrationExecutionRequest {
        Objects.requireNonNull(reviewSessionId, "reviewSessionId must not be null");
    }

    public static ApprovedMigrationExecutionRequest of(UUID reviewSessionId) {
        return new ApprovedMigrationExecutionRequest(
                reviewSessionId, null, null, null, null, null, true, null
        );
    }

    public static ApprovedMigrationExecutionRequest of(UUID reviewSessionId, UUID userId) {
        return new ApprovedMigrationExecutionRequest(
                reviewSessionId, userId, null, null, null, null, true, null
        );
    }

    public static ApprovedMigrationExecutionRequest of(UUID reviewSessionId, UUID userId, String targetPlaylistTitle) {
        return new ApprovedMigrationExecutionRequest(
                reviewSessionId, userId, null, null, targetPlaylistTitle, null, true, null
        );
    }

    public static ApprovedMigrationExecutionRequest ofSessionAndPlan(UUID reviewSessionId, UUID userId, MigrationPlan plan) {
        Objects.requireNonNull(plan, "MigrationPlan must not be null");
        return new ApprovedMigrationExecutionRequest(
                reviewSessionId, userId, plan.sourcePlaylistId(), plan.planId(),
                plan.sourcePlaylistTitle(), "Migrated from Spotify", true, plan
        );
    }

    public Optional<UUID> getUserId() {
        return Optional.ofNullable(userId);
    }

    public Optional<String> getSourcePlaylistId() {
        return Optional.ofNullable(sourcePlaylistId);
    }

    public Optional<UUID> getPlanId() {
        return Optional.ofNullable(planId);
    }

    public Optional<String> getTargetPlaylistTitle() {
        return Optional.ofNullable(targetPlaylistTitle);
    }

    public Optional<String> getTargetPlaylistDescription() {
        return Optional.ofNullable(targetPlaylistDescription);
    }

    public boolean isPrivatePlaylist() {
        return isPrivate != null ? isPrivate : true;
    }

    public Optional<MigrationPlan> getPlan() {
        return Optional.ofNullable(plan);
    }
}
