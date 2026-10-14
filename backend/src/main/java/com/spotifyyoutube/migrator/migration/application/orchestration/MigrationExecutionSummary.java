package com.spotifyyoutube.migrator.migration.application.orchestration;

import com.spotifyyoutube.migrator.migration.domain.MigrationStatus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable summary capturing the complete execution of a playlist migration workflow.
 *
 * @param jobId unique migration job identifier, or null if executed in direct evaluation mode
 * @param userId user identifier owning the migration
 * @param sourcePlaylistId source platform playlist identifier
 * @param sourcePlaylistTitle source playlist title
 * @param destinationPlaylistId destination platform playlist identifier
 * @param destinationPlaylistUrl public or direct URL to destination playlist, if available
 * @param finalStatus overall migration outcome status (COMPLETED, PARTIALLY_COMPLETED, FAILED)
 * @param totalTracks total source tracks evaluated
 * @param processedTracks count of successfully matched and inserted tracks
 * @param failedTracks count of failed, unmatched, or review-required tracks
 * @param duplicateTracks count of duplicate candidates skipped by deduplication policy
 * @param reviewRequiredTracks count of ambiguous tracks held back for manual review
 * @param durationMs total migration execution duration in milliseconds
 * @param trackOutcomes unmodifiable list of per-track migration outcomes in source order
 */
public record MigrationExecutionSummary(
        UUID jobId,
        UUID userId,
        String sourcePlaylistId,
        String sourcePlaylistTitle,
        String destinationPlaylistId,
        String destinationPlaylistUrl,
        MigrationStatus finalStatus,
        int totalTracks,
        int processedTracks,
        int failedTracks,
        int duplicateTracks,
        int reviewRequiredTracks,
        long durationMs,
        List<TrackMigrationOutcome> trackOutcomes
) {
    public MigrationExecutionSummary {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(sourcePlaylistId, "sourcePlaylistId must not be null");
        Objects.requireNonNull(finalStatus, "finalStatus must not be null");
        if (totalTracks < 0 || processedTracks < 0 || failedTracks < 0 || duplicateTracks < 0 || reviewRequiredTracks < 0) {
            throw new IllegalArgumentException("Track counts must be non-negative");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be non-negative: " + durationMs);
        }
        trackOutcomes = trackOutcomes != null ? List.copyOf(trackOutcomes) : List.of();
    }

    public MigrationExecutionSummary(
            String sourcePlaylistId,
            String destinationPlaylistId,
            int totalTracks,
            int processedTracks,
            int failedTracks,
            int duplicateTracks,
            int reviewRequiredTracks,
            long durationMs,
            List<TrackMigrationOutcome> trackOutcomes
    ) {
        this(
                null,
                UUID.randomUUID(),
                sourcePlaylistId,
                "Playlist " + sourcePlaylistId,
                destinationPlaylistId,
                "https://youtube.com/playlist?list=" + destinationPlaylistId,
                failedTracks == 0 ? MigrationStatus.COMPLETED : (processedTracks > 0 ? MigrationStatus.PARTIALLY_COMPLETED : MigrationStatus.FAILED),
                totalTracks,
                processedTracks,
                failedTracks,
                duplicateTracks,
                reviewRequiredTracks,
                durationMs,
                trackOutcomes
        );
    }

    public Optional<UUID> getJobId() {
        return Optional.ofNullable(jobId);
    }

    public Optional<String> getSourcePlaylistTitle() {
        return Optional.ofNullable(sourcePlaylistTitle);
    }

    public Optional<String> getDestinationPlaylistId() {
        return Optional.ofNullable(destinationPlaylistId);
    }

    public Optional<String> getDestinationPlaylistUrl() {
        return Optional.ofNullable(destinationPlaylistUrl);
    }

    public double getSuccessRate() {
        return totalTracks > 0 ? ((double) processedTracks) / totalTracks : 0.0;
    }

    public double getFailureRate() {
        return totalTracks > 0 ? ((double) failedTracks) / totalTracks : 0.0;
    }

    public double getReviewRate() {
        return totalTracks > 0 ? ((double) reviewRequiredTracks) / totalTracks : 0.0;
    }
}
