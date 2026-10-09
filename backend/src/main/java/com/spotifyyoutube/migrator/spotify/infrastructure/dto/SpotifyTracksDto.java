package com.spotifyyoutube.migrator.spotify.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SpotifyTracksDto(
        @JsonProperty("href") String href,
        @JsonProperty("total") Integer total
) {}
