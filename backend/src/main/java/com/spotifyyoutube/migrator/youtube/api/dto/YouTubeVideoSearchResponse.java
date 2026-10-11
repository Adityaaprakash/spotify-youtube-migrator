package com.spotifyyoutube.migrator.youtube.api.dto;

import java.util.List;

public record YouTubeVideoSearchResponse(
        List<YouTubeVideoSummaryResponse> items,
        String nextPageToken,
        String prevPageToken,
        int totalResults,
        int resultsPerPage
) {}
