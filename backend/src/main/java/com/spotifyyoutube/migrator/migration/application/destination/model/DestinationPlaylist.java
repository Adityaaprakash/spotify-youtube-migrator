package com.spotifyyoutube.migrator.migration.application.destination.model;

import com.spotifyyoutube.migrator.playlist.domain.Platform;

public record DestinationPlaylist(
        String id,
        String title,
        String description,
        String privacyStatus,
        String url,
        Platform platform
) {}
