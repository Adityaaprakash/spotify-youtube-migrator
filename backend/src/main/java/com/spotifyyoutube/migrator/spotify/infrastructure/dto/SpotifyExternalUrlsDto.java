package com.spotifyyoutube.migrator.spotify.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SpotifyExternalUrlsDto(
        @JsonProperty("spotify") String spotify
) {}
