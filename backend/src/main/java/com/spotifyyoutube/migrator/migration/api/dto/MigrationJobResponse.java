package com.spotifyyoutube.migrator.migration.api.dto;

import com.spotifyyoutube.migrator.migration.domain.MigrationStatus;
import com.spotifyyoutube.migrator.playlist.domain.Platform;

import java.time.Instant;
import java.util.UUID;

public record MigrationJobResponse(
        UUID id,
        UUID userId,
        String sourcePlaylistId,
        Platform sourcePlatform,
        Platform targetPlatform,
        MigrationStatus status,
        Integer totalTracks,
        Integer processedTracks,
        Integer failedTracks,
        Instant startedAt,
        Instant completedAt,
        String targetPlaylistUrl
) {}
