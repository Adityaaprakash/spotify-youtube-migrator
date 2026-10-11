package com.spotifyyoutube.migrator.migration.application.destination.model;

import java.util.List;

public record DestinationSearchResult(
        List<DestinationTrackCandidate> candidates,
        String nextPageToken,
        Integer totalResults
) {}
