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
 * Immutable domain summary representing a durable migration execution and its per-track records (Phase 11E/11F).
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
        int rolledBackInsertions,
        boolean fullySuccessful,
        boolean canResume,
        boolean canRollback,
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

    /**
     * Backward-compatible 19-parameter constructor (Phase 11E).
     */
    public MigrationExecutionSummary(
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
        this(
                executionId,
                reviewSessionId,
                planId,
                userId,
                destinationPlatform,
                destinationPlaylistId,
                destinationPlaylistUrl,
                sourcePlaylistTitle,
                status,
                totalApprovedTracks,
                successfulInsertions,
                failedInsertions,
                uncertainInsertions,
                skippedInsertions,
                0,
                fullySuccessful,
                canResume,
                status.canRollback() && (successfulInsertions > 0),
                startedAt,
                completedAt,
                trackRecords
        );
    }

    /**
     * Overloaded constructor accepting Optionals and String platform for test convenience.
     */
    public MigrationExecutionSummary(
            UUID executionId,
            UUID reviewSessionId,
            Optional<UUID> planId,
            UUID userId,
            String destinationPlatform,
            String destinationPlaylistId,
            Optional<String> destinationPlaylistUrl,
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
        this(
                executionId,
                reviewSessionId,
                planId != null ? planId.orElse(null) : null,
                userId,
                destinationPlatform != null ? Platform.valueOf(destinationPlatform.trim().toUpperCase()) : Platform.YOUTUBE,
                destinationPlaylistId,
                destinationPlaylistUrl != null ? destinationPlaylistUrl.orElse(null) : null,
                sourcePlaylistTitle,
                status,
                totalApprovedTracks,
                successfulInsertions,
                failedInsertions,
                uncertainInsertions,
                skippedInsertions,
                0,
                fullySuccessful,
                canResume,
                status.canRollback() && (successfulInsertions > 0),
                startedAt,
                completedAt,
                trackRecords
        );
    }

    /**
     * Overloaded 14-parameter constructor for test and worker convenience.
     */
    public MigrationExecutionSummary(
            UUID executionId,
            UUID reviewSessionId,
            MigrationExecutionStatus status,
            String destinationPlaylistId,
            String destinationPlaylistUrl,
            int totalApprovedTracks,
            int successfulInsertions,
            int failedInsertions,
            int uncertainInsertions,
            int skippedInsertions,
            int rolledBackInsertions,
            Instant startedAt,
            Instant completedAt,
            long version
    ) {
        this(
                executionId,
                reviewSessionId,
                null,
                reviewSessionId,
                Platform.YOUTUBE,
                destinationPlaylistId,
                destinationPlaylistUrl,
                "Source Playlist",
                status,
                totalApprovedTracks,
                successfulInsertions,
                failedInsertions,
                uncertainInsertions,
                skippedInsertions,
                rolledBackInsertions,
                status == MigrationExecutionStatus.COMPLETED && successfulInsertions == totalApprovedTracks,
                status.canResume(),
                status.canRollback() && (successfulInsertions > 0),
                startedAt,
                completedAt,
                Collections.emptyList()
        );
    }

    /**
     * Overloaded 17-parameter constructor for test convenience.
     */
    public MigrationExecutionSummary(
            UUID executionId,
            UUID reviewSessionId,
            UUID planId,
            String sourcePlaylistId,
            String sourcePlaylistTitle,
            String destinationPlaylistId,
            String destinationPlaylistUrl,
            MigrationExecutionStatus status,
            int totalApprovedTracks,
            int successfulInsertions,
            int failedInsertions,
            int uncertainInsertions,
            int skippedInsertions,
            int rolledBackInsertions,
            Instant startedAt,
            Instant completedAt,
            List<ExecutionTrackRecordDto> trackRecords
    ) {
        this(
                executionId,
                reviewSessionId,
                planId,
                reviewSessionId,
                Platform.YOUTUBE,
                destinationPlaylistId,
                destinationPlaylistUrl,
                sourcePlaylistTitle != null ? sourcePlaylistTitle : "Source Playlist",
                status,
                totalApprovedTracks,
                successfulInsertions,
                failedInsertions,
                uncertainInsertions,
                skippedInsertions,
                rolledBackInsertions,
                status == MigrationExecutionStatus.COMPLETED && successfulInsertions == totalApprovedTracks,
                status.canResume(),
                status.canRollback() && (successfulInsertions > 0),
                startedAt,
                completedAt,
                trackRecords
        );
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
                execution.getRolledBackInsertions(),
                fullySuccessful,
                execution.canResume(),
                execution.canRollback(),
                execution.getStartedAt(),
                execution.getCompletedAt(),
                trackDtos
        );
    }
}
