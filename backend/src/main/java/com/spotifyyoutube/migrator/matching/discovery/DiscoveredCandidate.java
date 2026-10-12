package com.spotifyyoutube.migrator.matching.discovery;

import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;

import java.util.Objects;

/**
 * Pairs a discovered {@link DestinationTrackCandidate} with the {@link SearchQuery} that discovered it.
 * Preserves candidate provenance for logging, telemetry, and debugging without modifying the underlying domain model.
 *
 * @param candidate the provider-neutral destination track candidate
 * @param discoveredBy the search query that yielded this candidate
 */
public record DiscoveredCandidate(
        DestinationTrackCandidate candidate,
        SearchQuery discoveredBy
) {
    public DiscoveredCandidate {
        Objects.requireNonNull(candidate, "DestinationTrackCandidate must not be null");
        Objects.requireNonNull(discoveredBy, "SearchQuery must not be null");
    }

    public String id() {
        return candidate.id();
    }

    public String title() {
        return candidate.title();
    }

    public String channelOrArtist() {
        return candidate.channelOrArtist();
    }

    public String description() {
        return candidate.description();
    }

    public String thumbnailUrl() {
        return candidate.thumbnailUrl();
    }

    public String publishedAt() {
        return candidate.publishedAt();
    }
}
