package com.spotifyyoutube.migrator.spotify.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record SpotifyTrackDto(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("duration_ms") Integer durationMs,
        @JsonProperty("uri") String uri,
        @JsonProperty("is_local") Boolean isLocal,
        @JsonProperty("artists") List<SpotifyArtistDto> artists,
        @JsonProperty("album") SpotifyAlbumDto album
) {}
