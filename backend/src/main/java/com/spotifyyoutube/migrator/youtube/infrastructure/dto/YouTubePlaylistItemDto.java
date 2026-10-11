package com.spotifyyoutube.migrator.youtube.infrastructure.dto;

public record YouTubePlaylistItemDto(
        String kind,
        String etag,
        String id,
        YouTubePlaylistItemSnippetResponseDto snippet
) {}
