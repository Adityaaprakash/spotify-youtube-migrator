package com.spotifyyoutube.migrator.migration.reliability.reconciliation;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of ambiguous track insertion reconciliation (Phase 13F).
 */
public record TrackInsertionReconciliationResult(
        TrackInsertionReconciliationOutcome outcome,
        String destinationPlaylistItemId,
        String diagnosticMessage
) {

    public TrackInsertionReconciliationResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
    }

    public static TrackInsertionReconciliationResult verifiedAdded(String playlistItemId, String message) {
        Objects.requireNonNull(playlistItemId, "playlistItemId must not be null for verified insertion");
        return new TrackInsertionReconciliationResult(
                TrackInsertionReconciliationOutcome.VERIFIED_ADDED,
                playlistItemId,
                message
        );
    }

    public static TrackInsertionReconciliationResult confirmedAbsent(String message) {
        return new TrackInsertionReconciliationResult(
                TrackInsertionReconciliationOutcome.CONFIRMED_ABSENT,
                null,
                message
        );
    }

    public static TrackInsertionReconciliationResult integrityViolation(String message) {
        return new TrackInsertionReconciliationResult(
                TrackInsertionReconciliationOutcome.INTEGRITY_VIOLATION,
                null,
                message
        );
    }

    public static TrackInsertionReconciliationResult inspectionFailed(String message) {
        return new TrackInsertionReconciliationResult(
                TrackInsertionReconciliationOutcome.INSPECTION_FAILED,
                null,
                message
        );
    }

    public Optional<String> getDestinationPlaylistItemId() {
        return Optional.ofNullable(destinationPlaylistItemId);
    }
}
