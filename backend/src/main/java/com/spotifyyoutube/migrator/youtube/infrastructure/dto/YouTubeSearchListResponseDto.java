package com.spotifyyoutube.migrator.youtube.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record YouTubeSearchListResponseDto(
        String kind,
        String etag,
        String nextPageToken,
        String prevPageToken,
        String regionCode,
        YouTubePageInfoDto pageInfo,
        List<YouTubeSearchResultDto> items
) {}
