package com.spotifyyoutube.migrator.migration.application.destination.model;

public record DestinationPlaylistItem(
        String id,
        String playlistId,
        String trackId,
        Integer position
) {}
