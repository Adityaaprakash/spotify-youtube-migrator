package com.spotifyyoutube.migrator.spotify.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SpotifyArtistDto(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name
) {}
