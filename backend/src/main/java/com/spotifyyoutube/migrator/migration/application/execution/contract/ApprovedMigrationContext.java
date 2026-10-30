package com.spotifyyoutube.migrator.migration.application.execution.contract;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable domain contract representing the validated approved migration context ready for Phase 11 execution.
 * <p>
 * Contains the source playlist identity, target playlist configuration, authenticated owner,
 * and the ordered, immutable selection of approved tracks without any unreviewed or conflicted items.
 *
 * @param reviewSessionId unique review session identifier whose decisions were approved
 * @param planId unique planning session identifier from Phase 9 snapshot, if correlated
 * @param userId unique identifier of the authenticated user possessing destination authorization
 * @param sourcePlaylistId source platform playlist identifier (e.g. Spotify playlist ID)
 * @param sourcePlaylistTitle source playlist title at planning time
 * @param targetPlaylistTitle title to assign to the destination playlist
 * @param targetPlaylistDescription description for the destination playlist
 * @param isPrivate privacy setting for the destination playlist
 * @param approvedTracks unmodifiable list of approved tracks in deterministic destination order
 * @param totalSourceTracks total tracks evaluated in the source playlist
 * @param approvedTrackCount count of approved tracks (equals approvedTracks.size())
 * @param excludedTrackCount count of explicitly excluded source tracks
 * @param validatedAt UTC timestamp when validation succeeded and this context was frozen
 */
public record ApprovedMigrationContext(
        UUID reviewSessionId,
        UUID planId,
        UUID userId,
        String sourcePlaylistId,
        String sourcePlaylistTitle,
        String targetPlaylistTitle,
        String targetPlaylistDescription,
        boolean isPrivate,
        List<ApprovedTrack> approvedTracks,
        int totalSourceTracks,
        int approvedTrackCount,
        int excludedTrackCount,
        Instant validatedAt
) {

    public ApprovedMigrationContext {
        Objects.requireNonNull(reviewSessionId, "reviewSessionId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(sourcePlaylistId, "sourcePlaylistId must not be null");
        if (sourcePlaylistId.isBlank()) {
            throw new IllegalArgumentException("sourcePlaylistId must not be blank");
        }
        Objects.requireNonNull(sourcePlaylistTitle, "sourcePlaylistTitle must not be null");
        Objects.requireNonNull(targetPlaylistTitle, "targetPlaylistTitle must not be null");
        if (targetPlaylistTitle.isBlank()) {
            throw new IllegalArgumentException("targetPlaylistTitle must not be blank");
        }
        Objects.requireNonNull(validatedAt, "validatedAt must not be null");

        approvedTracks = approvedTracks != null ? List.copyOf(approvedTracks) : List.of();

        if (totalSourceTracks < 0) {
            throw new IllegalArgumentException("totalSourceTracks must not be negative: " + totalSourceTracks);
        }
        if (approvedTrackCount != approvedTracks.size()) {
            throw new IllegalArgumentException(String.format(
                    "approvedTrackCount (%d) does not match approvedTracks size (%d)",
                    approvedTrackCount, approvedTracks.size()
            ));
        }
        if (excludedTrackCount < 0) {
            throw new IllegalArgumentException("excludedTrackCount must not be negative: " + excludedTrackCount);
        }
        if (approvedTrackCount + excludedTrackCount > totalSourceTracks) {
            throw new IllegalArgumentException(String.format(
                    "Approved (%d) + excluded (%d) cannot exceed totalSourceTracks (%d)",
                    approvedTrackCount, excludedTrackCount, totalSourceTracks
            ));
        }

        // Enforce deterministic, gapless 0-indexed destination target positions
        // and monotonic source playlist encounter ordering
        int previousSourcePos = -1;
        for (int i = 0; i < approvedTracks.size(); i++) {
            ApprovedTrack track = approvedTracks.get(i);
            Objects.requireNonNull(track, "ApprovedTrack at target index " + i + " must not be null");
            if (track.targetPosition() != i) {
                throw new IllegalArgumentException(String.format(
                        "ApprovedTrack at index %d has out-of-sequence targetPosition %d", i, track.targetPosition()
                ));
            }
            if (track.sourcePosition() <= previousSourcePos) {
                throw new IllegalArgumentException(String.format(
                        "ApprovedTrack at index %d has non-monotonic sourcePosition %d (previous was %d)",
                        i, track.sourcePosition(), previousSourcePos
                ));
            }
            previousSourcePos = track.sourcePosition();
        }
    }

    public Optional<UUID> getPlanId() {
        return Optional.ofNullable(planId);
    }

    public Optional<String> getTargetPlaylistDescription() {
        return Optional.ofNullable(targetPlaylistDescription);
    }

    public boolean hasApprovedTracks() {
        return approvedTrackCount > 0;
    }

    public Optional<ApprovedTrack> getTrackAtTargetPosition(int targetPosition) {
        if (targetPosition >= 0 && targetPosition < approvedTracks.size()) {
            return Optional.of(approvedTracks.get(targetPosition));
        }
        return Optional.empty();
    }

    public Optional<ApprovedTrack> getTrackAtSourcePosition(int sourcePosition) {
        return approvedTracks.stream()
                .filter(t -> t.sourcePosition() == sourcePosition)
                .findFirst();
    }
}
