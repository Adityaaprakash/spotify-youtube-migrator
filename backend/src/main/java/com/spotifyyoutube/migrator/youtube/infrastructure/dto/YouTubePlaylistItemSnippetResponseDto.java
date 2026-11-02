package com.spotifyyoutube.migrator.youtube.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * YouTube Data API v3 playlistItem snippet response DTO.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record YouTubePlaylistItemSnippetResponseDto(
        String playlistId,
        Integer position,
        YouTubeResourceIdDto resourceId,
        String title,
        String description,
        Map<String, YouTubeThumbnailDto> thumbnails,
        String publishedAt
) {
    public YouTubePlaylistItemSnippetResponseDto(
            String playlistId,
            Integer position,
            YouTubeResourceIdDto resourceId,
            String title,
            String description,
            Map<String, YouTubeThumbnailDto> thumbnails
    ) {
        this(playlistId, position, resourceId, title, description, thumbnails, null);
    }
}
