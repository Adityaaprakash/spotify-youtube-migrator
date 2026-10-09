package com.spotifyyoutube.migrator.spotify.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SpotifyOwnerDto(
        @JsonProperty("id") String id,
        @JsonProperty("display_name") String displayName
) {}
