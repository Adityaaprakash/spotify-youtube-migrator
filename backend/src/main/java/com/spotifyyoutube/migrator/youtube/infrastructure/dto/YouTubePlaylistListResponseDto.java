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

    public YouTubePlaylistListResponseDto(
            String kind,
            String etag,
            String nextPageToken,
            String prevPageToken,
            YouTubePageInfoDto pageInfo,
            List<YouTubePlaylistDto> items
    ) {
        this(kind, etag, items, pageInfo, nextPageToken);
    }
}
