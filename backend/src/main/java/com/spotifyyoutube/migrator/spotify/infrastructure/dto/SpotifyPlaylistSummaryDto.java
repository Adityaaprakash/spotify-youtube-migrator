package com.spotifyyoutube.migrator.spotify.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record SpotifyPlaylistSummaryDto(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("owner") SpotifyOwnerDto owner,
        @JsonProperty("images") List<SpotifyImageDto> images,
        @JsonProperty("tracks") SpotifyTracksDto tracks,
        @JsonProperty("external_urls") SpotifyExternalUrlsDto externalUrls
) {}
