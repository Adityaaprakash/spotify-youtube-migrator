package com.spotifyyoutube.migrator.youtube.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record YouTubePlaylistListResponseDto(
        String kind,
        String etag,
        List<YouTubePlaylistDto> items,
        YouTubePageInfoDto pageInfo,
        String nextPageToken
) {
    public YouTubePlaylistListResponseDto(
            String kind,
            String etag,
            List<YouTubePlaylistDto> items,
            YouTubePageInfoDto pageInfo
    ) {
        this(kind, etag, items, pageInfo, null);
    }
}
