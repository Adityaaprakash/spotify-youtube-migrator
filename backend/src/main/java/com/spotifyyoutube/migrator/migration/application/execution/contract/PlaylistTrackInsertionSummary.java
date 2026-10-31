package com.spotifyyoutube.migrator.migration.application.execution.contract;

import com.spotifyyoutube.migrator.playlist.domain.Platform;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable domain contract representing the comprehensive execution summary of ordered track insertions
 * into a destination playlist.
 * <p>
 * Enforces strict mathematical reconciliation across all track outcomes and verifies that outcomes
 * match the sequential target position ordering.
 *
 * @param reviewSessionId unique review session identifier whose decisions were executed
 * @param planId unique planning session identifier from Phase 9 snapshot, if correlated
 * @param userId unique identifier of the authenticated user
 * @param destinationPlatform target platform enum (e.g. Platform.YOUTUBE)
 * @param destinationPlaylistId platform identifier of the target playlist
 * @param totalApprovedTracks total count of approved tracks evaluated for insertion
 * @param successfulInsertions count of confirmed successful track insertions
 * @param failedInsertions count of confirmed failed track insertions
 * @param uncertainInsertions count of tracks with ambiguous/timed-out insertion outcomes
 * @param skippedInsertions count of tracks skipped due to prior failure or timeout
 * @param trackOutcomes unmodifiable list of granular per-track outcomes in destination order
 * @param startedAt timestamp when track insertion began
 * @param completedAt timestamp when track insertion finished
 */
public record PlaylistTrackInsertionSummary(
        UUID reviewSessionId,
        UUID planId,
        UUID userId,
        Platform destinationPlatform,
        String destinationPlaylistId,
        int totalApprovedTracks,
        int successfulInsertions,
        int failedInsertions,
        int uncertainInsertions,
        int skippedInsertions,
        List<TrackInsertionOutcome> trackOutcomes,
        Instant startedAt,
        Instant completedAt
) {

    public PlaylistTrackInsertionSummary {
        Objects.requireNonNull(reviewSessionId, "reviewSessionId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(destinationPlatform, "destinationPlatform must not be null");
        Objects.requireNonNull(destinationPlaylistId, "destinationPlaylistId must not be null");
        if (destinationPlaylistId.isBlank()) {
            throw new IllegalArgumentException("destinationPlaylistId must not be blank");
        }
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");

        trackOutcomes = trackOutcomes != null ? List.copyOf(trackOutcomes) : List.of();

        if (totalApprovedTracks < 0) {
            throw new IllegalArgumentException("totalApprovedTracks must not be negative: " + totalApprovedTracks);
        }
        if (successfulInsertions < 0) {
            throw new IllegalArgumentException("successfulInsertions must not be negative: " + successfulInsertions);
        }
        if (failedInsertions < 0) {
            throw new IllegalArgumentException("failedInsertions must not be negative: " + failedInsertions);
        }
        if (uncertainInsertions < 0) {
            throw new IllegalArgumentException("uncertainInsertions must not be negative: " + uncertainInsertions);
        }
        if (skippedInsertions < 0) {
            throw new IllegalArgumentException("skippedInsertions must not be negative: " + skippedInsertions);
        }

        int sum = successfulInsertions + failedInsertions + uncertainInsertions + skippedInsertions;
        if (sum != totalApprovedTracks) {
            throw new IllegalArgumentException(String.format(
                    "Outcome sum (%d: success=%d, failed=%d, uncertain=%d, skipped=%d) does not match totalApprovedTracks (%d)",
                    sum, successfulInsertions, failedInsertions, uncertainInsertions, skippedInsertions, totalApprovedTracks
            ));
        }

        if (trackOutcomes.size() != totalApprovedTracks) {
            throw new IllegalArgumentException(String.format(
                    "trackOutcomes size (%d) does not match totalApprovedTracks (%d)",
                    trackOutcomes.size(), totalApprovedTracks
            ));
        }

        // Verify sequential target position ordering
        for (int i = 0; i < trackOutcomes.size(); i++) {
            TrackInsertionOutcome outcome = trackOutcomes.get(i);
            Objects.requireNonNull(outcome, "TrackInsertionOutcome at index " + i + " must not be null");
            if (outcome.targetPosition() != i) {
                throw new IllegalArgumentException(String.format(
                        "TrackInsertionOutcome at index %d has out-of-sequence targetPosition %d",
                        i, outcome.targetPosition()
                ));
            }
        }
    }

    public Optional<UUID> getPlanId() {
        return Optional.ofNullable(planId);
    }

    public boolean isFullySuccessful() {
        return totalApprovedTracks > 0
                && successfulInsertions == totalApprovedTracks
                && failedInsertions == 0
                && uncertainInsertions == 0
                && skippedInsertions == 0;
    }

    public boolean hasFailures() {
        return failedInsertions > 0;
    }

    public boolean hasUncertain() {
        return uncertainInsertions > 0;
    }

    public boolean hasSkipped() {
        return skippedInsertions > 0;
    }

    public String getExecutionStatus() {
        if (isFullySuccessful()) {
            return "COMPLETED";
        }
        if (successfulInsertions > 0) {
            return "PARTIALLY_COMPLETED";
        }
        return "FAILED";
    }
}
