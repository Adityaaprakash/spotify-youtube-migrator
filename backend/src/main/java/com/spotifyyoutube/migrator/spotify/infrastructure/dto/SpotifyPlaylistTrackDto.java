package com.spotifyyoutube.migrator.spotify.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SpotifyPlaylistTrackDto(
        @JsonProperty("is_local") Boolean isLocal,
        @JsonProperty("track") SpotifyTrackDto track
) {}
