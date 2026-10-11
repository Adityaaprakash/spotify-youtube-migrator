package com.spotifyyoutube.migrator.youtube.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record YouTubePlaylistItemSnippetRequestDto(
        String playlistId,
        YouTubeResourceIdDto resourceId,
        Integer position
) {}
