package com.spotifyyoutube.migrator.youtube.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record YouTubePlaylistItemDto(
        String kind,
        String etag,
        String id,
        YouTubePlaylistItemSnippetResponseDto snippet
) {}
