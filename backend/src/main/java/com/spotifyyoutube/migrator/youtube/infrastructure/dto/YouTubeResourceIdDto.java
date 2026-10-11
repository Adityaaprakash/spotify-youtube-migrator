package com.spotifyyoutube.migrator.youtube.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record YouTubeResourceIdDto(
        String kind,
        String videoId,
        String channelId,
        String playlistId
) {}
