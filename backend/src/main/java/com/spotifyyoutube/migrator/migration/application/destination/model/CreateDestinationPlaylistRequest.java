package com.spotifyyoutube.migrator.migration.application.destination.model;

public record CreateDestinationPlaylistRequest(
        String title,
        String description,
        boolean isPrivate
) {}
