package com.spotifyyoutube.migrator.migration.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.progress.domain.MigrationProgressSnapshot;

import java.util.Objects;
import java.util.UUID;

/**
 * REST response payload exposing execution progress and track-level outcome distribution (Phase 12E).
 *
 * @param executionId        the unique identifier of the migration execution
 * @param status             the current lifecycle status of the execution
 * @param totalTracks        the total number of approved tracks
 * @param pendingTracks      the count of tracks waiting to be processed (status = PENDING)
 * @param addingTracks       the count of tracks currently in-flight to the destination (status = ADDING)
 * @param addedTracks        the count of tracks successfully inserted (status = ADDED)
 * @param failedTracks       the count of tracks with confirmed insertion failure (status = FAILED)
 * @param uncertainTracks    the count of tracks with ambiguous remote outcomes (status = UNCERTAIN)
 * @param skippedTracks      the count of tracks skipped to preserve order (status = SKIPPED)
 * @param rollingBackTracks  the count of tracks with compensating deletion in-flight (status = ROLLING_BACK)
 * @param rolledBackTracks   the count of tracks rolled back (status = ROLLED_BACK)
 * @param processedTracks    the count of tracks reaching a settled outcome (added + failed + uncertain + skipped + rolledBack)
 * @param remainingTracks    the count of tracks remaining to be processed
 * @param progressPercentage the completion percentage bounded in [0.0, 100.0]
 */
public record MigrationProgressResponse(
        @JsonAlias({"migrationId"})
        UUID executionId,

        @JsonAlias({"executionStatus"})
        MigrationExecutionStatus status,

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

    public MigrationProgressResponse {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    public static MigrationProgressResponse from(MigrationProgressSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "MigrationProgressSnapshot must not be null");
        return new MigrationProgressResponse(
                snapshot.executionId(),
                snapshot.executionStatus(),
                snapshot.totalTracks(),
                snapshot.pendingTracks(),
                snapshot.addingTracks(),
                snapshot.addedTracks(),
                snapshot.failedTracks(),
                snapshot.uncertainTracks(),
                snapshot.skippedTracks(),
                snapshot.rollingBackTracks(),
                snapshot.rolledBackTracks(),
                snapshot.processedTracks(),
                snapshot.remainingTracks(),
                snapshot.progressPercentage()
        );
    }
}
