package com.spotifyyoutube.migrator.migration.progress.domain;

import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain snapshot representing the current progress and per-track distribution of a migration execution (Phase 12D).
 *
 * @param executionId        the unique identifier of the migration execution
 * @param executionStatus    the current lifecycle status of the execution
 * @param totalTracks        the total number of approved tracks belonging to this execution
 * @param pendingTracks      the number of tracks waiting to be processed (status = PENDING)
 * @param addingTracks       the number of tracks currently in-flight to the destination provider (status = ADDING)
 * @param addedTracks        the number of tracks confirmed added to the destination playlist (status = ADDED)
 * @param failedTracks       the number of tracks with confirmed insertion failures (status = FAILED)
 * @param uncertainTracks    the number of tracks with ambiguous remote outcomes (status = UNCERTAIN)
 * @param skippedTracks      the number of tracks skipped to preserve canonical order (status = SKIPPED)
 * @param rollingBackTracks  the number of tracks with compensating deletions in-flight (status = ROLLING_BACK)
 * @param rolledBackTracks   the number of tracks successfully removed during rollback (status = ROLLED_BACK)
 * @param processedTracks    the total number of tracks that reached settled outcomes (added + failed + uncertain + skipped + rolledBack)
 * @param remainingTracks    the number of tracks remaining to be processed (totalTracks - processedTracks)
 * @param progressPercentage the percentage of tracks processed (0.0 to 100.0, rounded to 1 decimal place)
 */
public record MigrationProgressSnapshot(
        UUID executionId,
        MigrationExecutionStatus executionStatus,
        int totalTracks,
        int pendingTracks,
        int addingTracks,
        int addedTracks,
        int failedTracks,
        int uncertainTracks,
        int skippedTracks,
        int rollingBackTracks,
        int rolledBackTracks,
        int processedTracks,
        int remainingTracks,
        double progressPercentage
) {

    public MigrationProgressSnapshot {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(executionStatus, "executionStatus must not be null");

        validateNonNegative("totalTracks", totalTracks);
        validateNonNegative("pendingTracks", pendingTracks);
        validateNonNegative("addingTracks", addingTracks);
        validateNonNegative("addedTracks", addedTracks);
        validateNonNegative("failedTracks", failedTracks);
        validateNonNegative("uncertainTracks", uncertainTracks);
        validateNonNegative("skippedTracks", skippedTracks);
        validateNonNegative("rollingBackTracks", rollingBackTracks);
        validateNonNegative("rolledBackTracks", rolledBackTracks);
        validateNonNegative("processedTracks", processedTracks);
        validateNonNegative("remainingTracks", remainingTracks);

        if (Double.isNaN(progressPercentage) || Double.isInfinite(progressPercentage)) {
            throw new IllegalArgumentException("progressPercentage must be a finite number");
        }
        if (progressPercentage < 0.0 || progressPercentage > 100.0) {
            throw new IllegalArgumentException("progressPercentage must be between 0.0 and 100.0, got: " + progressPercentage);
        }
    }

    /**
     * Factory method computing processed tracks, remaining tracks, and progress percentage from track counts.
     *
     * @param executionId       the execution ID
     * @param executionStatus   the execution status
     * @param totalTracks       the total tracks in the execution
     * @param pendingTracks     count of PENDING tracks
     * @param addingTracks      count of ADDING tracks
     * @param addedTracks       count of ADDED tracks
     * @param failedTracks      count of FAILED tracks
     * @param uncertainTracks   count of UNCERTAIN tracks
     * @param skippedTracks     count of SKIPPED tracks
     * @param rollingBackTracks count of ROLLING_BACK tracks
     * @param rolledBackTracks  count of ROLLED_BACK tracks
     * @return immutable validated progress snapshot
     */
    public static MigrationProgressSnapshot calculate(
            UUID executionId,
            MigrationExecutionStatus executionStatus,
            int totalTracks,
            int pendingTracks,
            int addingTracks,
            int addedTracks,
            int failedTracks,
            int uncertainTracks,
            int skippedTracks,
            int rollingBackTracks,
            int rolledBackTracks
    ) {
        validateNonNegative("totalTracks", totalTracks);
        validateNonNegative("pendingTracks", pendingTracks);
        validateNonNegative("addingTracks", addingTracks);
        validateNonNegative("addedTracks", addedTracks);
        validateNonNegative("failedTracks", failedTracks);
        validateNonNegative("uncertainTracks", uncertainTracks);
        validateNonNegative("skippedTracks", skippedTracks);
        validateNonNegative("rollingBackTracks", rollingBackTracks);
        validateNonNegative("rolledBackTracks", rolledBackTracks);

        int processed = addedTracks + failedTracks + uncertainTracks + skippedTracks + rolledBackTracks;
        int remaining = Math.max(0, totalTracks - processed);
        double percentage = computePercentage(processed, totalTracks);

        return new MigrationProgressSnapshot(
                executionId,
                executionStatus,
                totalTracks,
                pendingTracks,
                addingTracks,
                addedTracks,
                failedTracks,
                uncertainTracks,
                skippedTracks,
                rollingBackTracks,
                rolledBackTracks,
                processed,
                remaining,
                percentage
        );
    }

    /**
     * Calculates the progress percentage safely avoiding division by zero and rounding to one decimal place.
     *
     * @param processed count of processed tracks
     * @param total     total track count
     * @return bounded percentage [0.0, 100.0]
     */
    private static double computePercentage(int processed, int total) {
        if (total <= 0) {
            return 0.0;
        }
        double raw = ((double) processed / (double) total) * 100.0;
        double clamped = Math.min(100.0, Math.max(0.0, raw));
        return BigDecimal.valueOf(clamped)
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static void validateNonNegative(String fieldName, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must not be negative, got: " + value);
        }
    }
}
