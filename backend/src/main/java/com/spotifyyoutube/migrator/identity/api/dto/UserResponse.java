package com.spotifyyoutube.migrator.identity.api.dto;

import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String spotifyId,
        String youtubeId
) {}
