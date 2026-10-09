package com.spotifyyoutube.migrator.spotify.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record SpotifyPagingDto<T>(
        @JsonProperty("href") String href,
        @JsonProperty("items") List<T> items,
        @JsonProperty("limit") Integer limit,
        @JsonProperty("offset") Integer offset,
        @JsonProperty("next") String next,
        @JsonProperty("previous") String previous,
        @JsonProperty("total") Integer total
) {}
