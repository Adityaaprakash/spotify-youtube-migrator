package com.spotifyyoutube.migrator.youtube.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record YouTubeSearchResultDto(
        String kind,
        String etag,
        YouTubeResourceIdDto id,
        YouTubeSearchSnippetDto snippet
) {}
