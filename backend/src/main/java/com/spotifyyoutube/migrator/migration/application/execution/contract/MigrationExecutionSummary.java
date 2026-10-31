package com.spotifyyoutube.migrator.migration.application.execution.contract;

import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.playlist.domain.Platform;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable domain summary representing a durable migration execution and its per-track records (Phase 11E).
 */
public record MigrationExecutionSummary(
        UUID executionId,
        UUID reviewSessionId,
        UUID planId,
        UUID userId,
        Platform destinationPlatform,
        String destinationPlaylistId,
        String destinationPlaylistUrl,
        String sourcePlaylistTitle,
        MigrationExecutionStatus status,
        int totalApprovedTracks,
        int successfulInsertions,
        int failedInsertions,
        int uncertainInsertions,
        int skippedInsertions,
        boolean fullySuccessful,
        boolean canResume,
        Instant startedAt,
        Instant completedAt,
        List<ExecutionTrackRecordDto> trackRecords
) {

    public MigrationExecutionSummary {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(reviewSessionId, "reviewSessionId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(destinationPlatform, "destinationPlatform must not be null");
        Objects.requireNonNull(destinationPlaylistId, "destinationPlaylistId must not be null");
        Objects.requireNonNull(status, "status must not be null");

        trackRecords = trackRecords != null
                ? Collections.unmodifiableList(trackRecords)
                : Collections.emptyList();
    }

    public Optional<UUID> getPlanId() {
        return Optional.ofNullable(planId);
    }

    public Optional<String> getDestinationPlaylistUrl() {
        return Optional.ofNullable(destinationPlaylistUrl);
    }

    public static MigrationExecutionSummary from(MigrationExecution execution) {
        if (execution == null) {
            return null;
        }

        List<ExecutionTrackRecordDto> trackDtos = execution.getTrackRecords().stream()
                .map(ExecutionTrackRecordDto::from)
                .toList();

        boolean fullySuccessful = execution.getStatus() == MigrationExecutionStatus.COMPLETED
                && execution.getSuccessfulInsertions() == execution.getTotalApprovedTracks();

        return new MigrationExecutionSummary(
                execution.getId(),
                execution.getReviewSession().getId(),
                execution.getPlanId(),
                execution.getUser().getId(),
                execution.getDestinationPlatform(),
                execution.getDestinationPlaylistId(),
                execution.getDestinationPlaylistUrl(),
                execution.getSourcePlaylistTitle(),
                execution.getStatus(),
                execution.getTotalApprovedTracks(),
                execution.getSuccessfulInsertions(),
                execution.getFailedInsertions(),
                execution.getUncertainInsertions(),
                execution.getSkippedInsertions(),
                fullySuccessful,
                execution.canResume(),
                execution.getStartedAt(),
                execution.getCompletedAt(),
                trackDtos
        );
    }
}
