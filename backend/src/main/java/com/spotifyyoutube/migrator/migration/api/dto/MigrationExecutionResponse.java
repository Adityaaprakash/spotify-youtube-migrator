package com.spotifyyoutube.migrator.migration.api.dto;

import com.spotifyyoutube.migrator.playlist.domain.Platform;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Presentation response DTO summarizing a durable migration execution (Phase 11E/11F).
 *
 * @param executionId unique execution identifier
 * @param reviewSessionId unique review session identifier
 * @param planId optional planning identifier from Phase 9
 * @param userId owner user ID
 * @param destinationPlatform target platform enum (e.g. YOUTUBE)
 * @param destinationPlaylistId destination playlist identifier
 * @param destinationPlaylistUrl web URL to access destination playlist
 * @param sourcePlaylistTitle title of the source playlist
 * @param status execution lifecycle status ("INITIALIZED", "RUNNING", "PARTIALLY_COMPLETED", "COMPLETED", "FAILED", "RECOVERY_REQUIRED", "ROLLED_BACK", "PARTIALLY_ROLLED_BACK")
 * @param totalApprovedTracks total approved tracks targeted for insertion
 * @param successfulInsertions count of tracks successfully inserted
 * @param failedInsertions count of tracks that failed insertion with confirmed provider rejection
 * @param uncertainInsertions count of tracks with ambiguous remote outcomes
 * @param skippedInsertions count of tracks skipped to preserve ordering integrity
 * @param rolledBackInsertions count of tracks that were rolled back (Phase 11F)
 * @param fullySuccessful true if and only if all approved tracks were successfully inserted
 * @param canResume true if this execution can safely be resumed to process pending tracks
 * @param canRollback true if this execution is eligible for rollback (Phase 11F)
 * @param startedAt timestamp when execution began
 * @param completedAt timestamp when execution finished
 * @param trackRecords detailed per-track durable execution records in canonical target position order
 */
public record MigrationExecutionResponse(
        UUID executionId,
        UUID reviewSessionId,
        UUID planId,
        UUID userId,
        Platform destinationPlatform,
        String destinationPlaylistId,
        String destinationPlaylistUrl,
        String sourcePlaylistTitle,
        String status,
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
        List<ExecutionTrackRecordResponse> trackRecords
) {

    /**
     * Backward-compatible 19-parameter constructor (Phase 11E).
     */
    public MigrationExecutionResponse(
            UUID executionId,
            UUID reviewSessionId,
            UUID planId,
            UUID userId,
            Platform destinationPlatform,
            String destinationPlaylistId,
            String destinationPlaylistUrl,
            String sourcePlaylistTitle,
            String status,
            int totalApprovedTracks,
            int successfulInsertions,
            int failedInsertions,
            int uncertainInsertions,
            int skippedInsertions,
            boolean fullySuccessful,
            boolean canResume,
            Instant startedAt,
            Instant completedAt,
            List<ExecutionTrackRecordResponse> trackRecords
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
                successfulInsertions > 0 && !"RUNNING".equals(status) && !"ROLLED_BACK".equals(status),
                startedAt,
                completedAt,
                trackRecords
        );
    }
}
