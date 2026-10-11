package com.spotifyyoutube.migrator.migration.application.destination.model;

public record DestinationTrackCandidate(
        String id,
        String title,
        String channelOrArtist,
        String description,
        String thumbnailUrl,
        String publishedAt
) {}
