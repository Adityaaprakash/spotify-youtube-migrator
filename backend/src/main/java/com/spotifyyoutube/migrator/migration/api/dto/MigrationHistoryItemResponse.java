package com.spotifyyoutube.migrator.migration.api.dto;

import com.spotifyyoutube.migrator.playlist.domain.Platform;

import java.time.Instant;
import java.util.UUID;

/**
 * Summary presentation response DTO representing a historical migration in a user's migration history list (Phase 14C).
 *
 * @param migrationId unique execution identifier
 * @param sourcePlaylistId source playlist identifier
 * @param sourcePlaylistTitle title of source playlist
 * @param destinationPlatform target platform (e.g. YOUTUBE)
 * @param destinationPlaylistId destination playlist identifier when allocated
 * @param destinationPlaylistUrl web access URL for destination playlist
 * @param status current lifecycle status
 * @param totalSourceTracks total tracks evaluated from source playlist
 * @param matchedTracks number of source tracks with approved match candidates
 * @param totalApprovedTracks count of tracks targeted for insertion
 * @param successfulInsertions count of tracks successfully inserted
 * @param failedInsertions count of tracks that permanently failed insertion
 * @param uncertainInsertions count of tracks with ambiguous remote outcomes
 * @param skippedInsertions count of tracks skipped
 * @param rolledBackInsertions count of tracks that were rolled back
 * @param createdAt timestamp when migration record was created
 * @param startedAt timestamp when execution began
 * @param completedAt timestamp when execution finished
 * @param failureSummary summary of failure or partial completion if applicable
 */
public record MigrationHistoryItemResponse(
        UUID migrationId,
        String sourcePlaylistId,
        String sourcePlaylistTitle,
        Platform destinationPlatform,
        String destinationPlaylistId,
        String destinationPlaylistUrl,
        String status,
        int totalSourceTracks,
        int matchedTracks,
        int totalApprovedTracks,
        int successfulInsertions,
        int failedInsertions,
        int uncertainInsertions,
        int skippedInsertions,
        int rolledBackInsertions,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        String failureSummary
) {
}
