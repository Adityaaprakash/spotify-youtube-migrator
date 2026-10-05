package com.spotifyyoutube.migrator.migration.api.dto;

import com.spotifyyoutube.migrator.migration.domain.MigrationStatus;

import java.util.UUID;

public record MigrationTaskResponse(
        UUID id,
        UUID jobId,
        String sourceTrackId,
        String targetTrackId,
        MigrationStatus status,
        String errorMessage
) {}
