package com.spotifyyoutube.migrator.spotify.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record SpotifyUserProfileDto(
        @JsonProperty("id") String id,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("email") String email,
        @JsonProperty("images") List<SpotifyImageDto> images
) {}
