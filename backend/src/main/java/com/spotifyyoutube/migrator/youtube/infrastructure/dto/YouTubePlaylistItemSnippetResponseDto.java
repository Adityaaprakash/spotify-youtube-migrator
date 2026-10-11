package com.spotifyyoutube.migrator.youtube.infrastructure.dto;

import java.util.Map;

public record YouTubePlaylistItemSnippetResponseDto(
        String playlistId,
        Integer position,
        YouTubeResourceIdDto resourceId,
        String title,
        String description,
        Map<String, YouTubeThumbnailDto> thumbnails
) {}
