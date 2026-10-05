package com.spotifyyoutube.migrator.playlist.api.dto;

import com.spotifyyoutube.migrator.playlist.domain.Platform;
import java.util.UUID;

public record PlaylistResponse(
        UUID id,
        String name,
        String description,
        Platform platform,
        String externalId,
        String url,
        Integer totalTracks
) {}
