package com.spotifyyoutube.migrator.migration.execution.repository;

import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;

import java.util.Objects;

/**
 * Projection record representing the count of track records in a specific execution track status (Phase 12D).
 *
 * @param status the execution track status
 * @param count  the number of tracks with this status
 */
public record TrackStatusCount(
        ExecutionTrackStatus status,
        long count
) {

    public TrackStatusCount {
        Objects.requireNonNull(status, "ExecutionTrackStatus must not be null");
    }
}
