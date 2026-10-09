package com.spotifyyoutube.migrator.spotify.api.dto;

import java.util.List;

public record SpotifyPageResponse<T>(
        List<T> items,
        Integer total,
        Integer limit,
        Integer offset,
        boolean hasNext
) {}
