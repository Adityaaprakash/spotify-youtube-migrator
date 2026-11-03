package com.spotifyyoutube.migrator.migration.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * REST response payload returned upon submitting an approved migration asynchronously (Phase 12E).
 *
 * @param executionId     unique identifier of the persisted migration execution
 * @param reviewSessionId unique identifier of the approved review session
 * @param status          initial lifecycle status (e.g. QUEUED or COMPLETED)
 * @param progressUrl     relative or absolute REST URL for polling migration progress
 * @param message         human-readable summary message
 * @param totalTracks     total count of approved tracks to be migrated
 * @param submittedAt     timestamp when the migration was accepted
 */
public record MigrationSubmissionResponse(
        @JsonAlias({"migrationId"})
        UUID executionId,

        UUID reviewSessionId,
        MigrationExecutionStatus status,
        String progressUrl,
        String message,
        int totalTracks,
        Instant submittedAt
) {

    public MigrationSubmissionResponse {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    public static MigrationSubmissionResponse from(MigrationExecutionSummary summary) {
        Objects.requireNonNull(summary, "MigrationExecutionSummary must not be null");
        String progressUrl = "/api/migrations/" + summary.executionId() + "/progress";
        String message = summary.status() == MigrationExecutionStatus.COMPLETED
                ? "Migration is already COMPLETED"
                : "Migration request accepted and queued for background processing";

        return new MigrationSubmissionResponse(
                summary.executionId(),
                summary.reviewSessionId(),
                summary.status(),
                progressUrl,
                message,
                summary.totalApprovedTracks(),
                Instant.now()
        );
    }

    public static MigrationSubmissionResponse of(
            UUID executionId,
            UUID reviewSessionId,
            MigrationExecutionStatus status,
            String progressUrl,
            String message,
            int totalTracks
    ) {
        return new MigrationSubmissionResponse(
                executionId,
                reviewSessionId,
                status,
                progressUrl,
                message,
                totalTracks,
                Instant.now()
        );
    }
}
