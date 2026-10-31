package com.spotifyyoutube.migrator.migration.api.dto;

import com.spotifyyoutube.migrator.playlist.domain.Platform;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API presentation response DTO summarizing the outcome of ordered destination track insertion (Phase 11D).
 *
 * @param reviewSessionId unique review session identifier
 * @param planId optional planning identifier from Phase 9
 * @param destinationPlatform target platform enum (e.g. YOUTUBE)
 * @param destinationPlaylistId destination playlist identifier
 * @param totalApprovedTracks total approved tracks targeted for insertion
 * @param successfulInsertions count of tracks successfully inserted
 * @param failedInsertions count of tracks that failed insertion with confirmed provider rejection
 * @param uncertainInsertions count of tracks with ambiguous remote outcomes (e.g. timeout)
 * @param skippedInsertions count of tracks skipped to preserve ordering integrity
 * @param fullySuccessful true if and only if all approved tracks were successfully inserted
 * @param executionStatus high-level execution outcome ("COMPLETED", "PARTIALLY_FAILED", "STOPPED_ON_UNCERTAIN_OUTCOME", "FAILED")
 * @param startedAt timestamp when track insertion started
 * @param completedAt timestamp when track insertion finished
 * @param trackOutcomes detailed per-track insertion outcomes in canonical target position order
 */
public record PlaylistTrackInsertionResponse(
        UUID reviewSessionId,
        UUID planId,
        Platform destinationPlatform,
        String destinationPlaylistId,
        int totalApprovedTracks,
        int successfulInsertions,
        int failedInsertions,
        int uncertainInsertions,
        int skippedInsertions,
        boolean fullySuccessful,
        String executionStatus,
        Instant startedAt,
        Instant completedAt,
        List<TrackInsertionOutcomeResponse> trackOutcomes
) {
}
