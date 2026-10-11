package com.spotifyyoutube.migrator.migration.application.destination.model;

public record AddTrackToDestinationPlaylistRequest(
        String trackId,
        Integer position
) {}
