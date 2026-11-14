package com.spotifyyoutube.migrator.migration.reliability.reconciliation;

import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of destination playlist creation reconciliation (Phase 13F).
 */
public record PlaylistReconciliationResult(
        PlaylistReconciliationOutcome outcome,
        DestinationPlaylist matchedPlaylist,
        List<DestinationPlaylist> conflictingPlaylists,
        String diagnosticMessage
) {

    public PlaylistReconciliationResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        conflictingPlaylists = conflictingPlaylists != null
                ? Collections.unmodifiableList(conflictingPlaylists)
                : Collections.emptyList();
    }

    public static PlaylistReconciliationResult exactMatch(DestinationPlaylist playlist) {
        Objects.requireNonNull(playlist, "playlist must not be null for exact match");
        return new PlaylistReconciliationResult(
                PlaylistReconciliationOutcome.EXACT_MATCH_FOUND,
                playlist,
                Collections.emptyList(),
                "Found matching destination playlist with exact operation correlation marker: " + playlist.id()
        );
    }

    public static PlaylistReconciliationResult conclusivelyAbsent(String diagnostic) {
        return new PlaylistReconciliationResult(
                PlaylistReconciliationOutcome.CONCLUSIVELY_ABSENT,
                null,
                Collections.emptyList(),
                diagnostic != null ? diagnostic : "No matching marker or conflicting title observed after bounded verification."
        );
    }

    public static PlaylistReconciliationResult titleCollision(DestinationPlaylist conflicting, String diagnostic) {
        return new PlaylistReconciliationResult(
                PlaylistReconciliationOutcome.TITLE_COLLISION,
                conflicting,
                conflicting != null ? List.of(conflicting) : Collections.emptyList(),
                diagnostic != null ? diagnostic : "Existing playlist shares target title but lacks required correlation marker."
        );
    }

    public static PlaylistReconciliationResult multipleMarkersFound(List<DestinationPlaylist> conflicting, String diagnostic) {
        return new PlaylistReconciliationResult(
                PlaylistReconciliationOutcome.MULTIPLE_MATCHING_MARKERS,
                null,
                conflicting,
                diagnostic != null ? diagnostic : "Multiple playlists discovered bearing the identical correlation marker."
        );
    }

    public static PlaylistReconciliationResult providerError(String diagnostic) {
        return new PlaylistReconciliationResult(
                PlaylistReconciliationOutcome.PROVIDER_ERROR,
                null,
                Collections.emptyList(),
                diagnostic != null ? diagnostic : "Failed to communicate with provider during reconciliation."
        );
    }

    public Optional<DestinationPlaylist> getMatchedPlaylist() {
        return Optional.ofNullable(matchedPlaylist);
    }
}
