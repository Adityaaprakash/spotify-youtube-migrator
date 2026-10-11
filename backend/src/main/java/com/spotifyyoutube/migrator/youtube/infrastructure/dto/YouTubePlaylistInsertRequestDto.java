package com.spotifyyoutube.migrator.youtube.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record YouTubePlaylistInsertRequestDto(
        YouTubePlaylistSnippetDto snippet,
        YouTubePlaylistStatusDto status
) {}
