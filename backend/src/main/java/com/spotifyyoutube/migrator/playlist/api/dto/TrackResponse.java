package com.spotifyyoutube.migrator.playlist.api.dto;

import java.util.UUID;

public record TrackResponse(
        UUID id,
        String name,
        String artist,
        String album,
        Integer durationMs,
        String externalId
) {}
