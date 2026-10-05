package com.spotifyyoutube.migrator.migration.api.dto;

import com.spotifyyoutube.migrator.playlist.domain.Platform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateMigrationRequest(
        @NotNull(message = "User ID must be provided")
        UUID userId, // Explicitly taking user ID since there is no security context holding the user yet

        @NotBlank(message = "Source playlist ID cannot be blank")
        String sourcePlaylistId,

        @NotNull(message = "Source platform is required")
        Platform sourcePlatform,

        @NotNull(message = "Target platform is required")
        Platform targetPlatform
) {}
