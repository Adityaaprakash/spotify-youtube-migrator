package com.spotifyyoutube.migrator.youtube.infrastructure.dto;

import java.util.List;

public record YouTubePlaylistListResponseDto(
        String kind,
        String etag,
        List<YouTubePlaylistDto> items,
        YouTubePageInfoDto pageInfo
) {}
