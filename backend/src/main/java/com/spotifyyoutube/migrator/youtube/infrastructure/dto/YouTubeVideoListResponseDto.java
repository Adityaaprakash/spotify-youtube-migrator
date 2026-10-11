package com.spotifyyoutube.migrator.youtube.infrastructure.dto;

import java.util.List;

public record YouTubeVideoListResponseDto(
        String kind,
        String etag,
        YouTubePageInfoDto pageInfo,
        List<YouTubeVideoItemDto> items
) {}
