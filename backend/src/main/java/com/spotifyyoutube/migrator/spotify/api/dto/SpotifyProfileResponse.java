package com.spotifyyoutube.migrator.spotify.api.dto;

public record SpotifyProfileResponse(
        String id,
        String displayName,
        String email,
        String imageUrl
) {}
