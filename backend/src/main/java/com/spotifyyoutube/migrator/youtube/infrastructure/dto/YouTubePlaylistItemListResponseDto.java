package com.spotifyyoutube.migrator.youtube.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * YouTube Data API v3 playlistItems.list response DTO (Phase 11G).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record YouTubePlaylistItemListResponseDto(
        String kind,
        String etag,
        String nextPageToken,
        String prevPageToken,
        YouTubePageInfoDto pageInfo,
        List<YouTubePlaylistItemDto> items
) {}
