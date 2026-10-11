package com.spotifyyoutube.migrator.youtube.infrastructure.dto;

public record YouTubeVideoItemDto(
        String kind,
        String etag,
        String id,
        YouTubeVideoSnippetDto snippet,
        YouTubeVideoContentDetailsDto contentDetails
) {}
