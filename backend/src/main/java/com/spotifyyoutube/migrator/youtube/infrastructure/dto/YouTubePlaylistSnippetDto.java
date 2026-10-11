package com.spotifyyoutube.migrator.youtube.infrastructure.dto;

import java.util.Map;

public record YouTubePlaylistSnippetDto(
        String title,
        String description,
        String publishedAt,
        String channelId,
        String channelTitle,
        Map<String, YouTubeThumbnailDto> thumbnails
) {}
