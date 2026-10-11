package com.spotifyyoutube.migrator.migration.application.destination.model;

public record DestinationPlaylistVerification(
        String playlistId,
        PlaylistVerificationStatus status,
        String message,
        DestinationPlaylist playlist
) {}
