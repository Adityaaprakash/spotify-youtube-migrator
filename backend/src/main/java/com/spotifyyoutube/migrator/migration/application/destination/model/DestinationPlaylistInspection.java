package com.spotifyyoutube.migrator.migration.application.destination.model;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot of a destination playlist's complete remote state obtained during inspection (Phase 11G).
 * <p>
 * Distinguishes complete remote playlist snapshots from incomplete or failed inspections.
 * Never interprets a failed inspection as an empty playlist or a partial response as a complete snapshot.
 */
public record DestinationPlaylistInspection(
        String playlistId,
        List<DestinationPlaylistItemDetails> items,
        int totalItemCount,
        boolean complete,
        Instant inspectedAt,
        String failureReason
) {
    public static DestinationPlaylistInspection complete(String playlistId, List<DestinationPlaylistItemDetails> items, Instant inspectedAt) {
        return new DestinationPlaylistInspection(
                playlistId,
                items != null ? List.copyOf(items) : List.of(),
                items != null ? items.size() : 0,
                true,
                inspectedAt != null ? inspectedAt : Instant.now(),
                null
        );
    }

    public static DestinationPlaylistInspection failed(String playlistId, String failureReason, Instant inspectedAt) {
        return new DestinationPlaylistInspection(
                playlistId,
                List.of(),
                0,
                false,
                inspectedAt != null ? inspectedAt : Instant.now(),
                failureReason != null ? failureReason : "Unknown inspection failure"
        );
    }
}
