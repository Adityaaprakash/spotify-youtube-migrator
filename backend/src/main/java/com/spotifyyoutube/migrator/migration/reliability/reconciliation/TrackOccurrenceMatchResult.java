package com.spotifyyoutube.migrator.migration.reliability.reconciliation;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of pure track occurrence matching (Phase 13F).
 */
public record TrackOccurrenceMatchResult(
        boolean isVerifiedAdded,
        String verifiedPlaylistItemId,
        boolean isConfirmedAbsent,
        boolean isIntegrityViolation,
        String message
) {

    public static TrackOccurrenceMatchResult verifiedAdded(String playlistItemId, String message) {
        Objects.requireNonNull(playlistItemId, "playlistItemId must not be null for verified insertion");
        return new TrackOccurrenceMatchResult(true, playlistItemId, false, false, message);
    }

    public static TrackOccurrenceMatchResult confirmedAbsent(String message) {
        return new TrackOccurrenceMatchResult(false, null, true, false, message);
    }

    public static TrackOccurrenceMatchResult integrityViolation(String message) {
        return new TrackOccurrenceMatchResult(false, null, false, true, message);
    }

    public Optional<String> getVerifiedPlaylistItemId() {
        return Optional.ofNullable(verifiedPlaylistItemId);
    }
}
