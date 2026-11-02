package com.spotifyyoutube.migrator.migration.application.destination.model;

import java.time.Instant;

/**
 * Detailed representation of an item retrieved from a remote destination playlist during inspection (Phase 11G).
 */
public record DestinationPlaylistItemDetails(
        String playlistItemId,
        String playlistId,
        String destinationTrackId,
        Integer position,
        Instant publishedAt,
        String title
) {}
