package com.spotifyyoutube.migrator.spotify.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SpotifyImageDto(
        @JsonProperty("url") String url,
        @JsonProperty("height") Integer height,
        @JsonProperty("width") Integer width
) {}
