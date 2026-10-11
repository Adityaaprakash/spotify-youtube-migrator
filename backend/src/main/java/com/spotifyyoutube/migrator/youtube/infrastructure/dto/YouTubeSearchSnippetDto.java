package com.spotifyyoutube.migrator.youtube.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record YouTubeSearchSnippetDto(
        String publishedAt,
        String channelId,
        String title,
        String description,
        Map<String, YouTubeThumbnailDto> thumbnails,
        String channelTitle,
        String liveBroadcastContent,
        String publishTime
) {}
