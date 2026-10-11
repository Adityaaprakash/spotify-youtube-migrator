package com.spotifyyoutube.migrator.youtube.infrastructure.dto;

public record YouTubePlaylistDto(
        String kind,
        String etag,
        String id,
        YouTubePlaylistSnippetDto snippet,
        YouTubePlaylistStatusDto status
) {}
