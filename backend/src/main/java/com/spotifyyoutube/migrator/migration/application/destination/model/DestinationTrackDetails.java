package com.spotifyyoutube.migrator.migration.application.destination.model;

public record DestinationTrackDetails(
        String id,
        String title,
        String channelOrArtist,
        String description,
        String thumbnailUrl,
        String publishedAt,
        String duration
) {}
