package com.spotifyyoutube.migrator.spotify.api.dto;

public record SpotifyPlaylistSummaryResponse(
        String id,
        String name,
        String description,
        String ownerDisplayName,
        String imageUrl,
        Integer totalTracks,
        String spotifyUrl
) {}
