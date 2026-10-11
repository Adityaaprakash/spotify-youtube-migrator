package com.spotifyyoutube.migrator.youtube.infrastructure.dto;

import java.util.Map;

public record YouTubeVideoSnippetDto(
        String publishedAt,
        String channelId,
        String title,
        String description,
        Map<String, YouTubeThumbnailDto> thumbnails,
        String channelTitle
) {}
