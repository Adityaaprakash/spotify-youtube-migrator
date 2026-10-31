package com.spotifyyoutube.migrator.migration.application.execution.contract;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable domain contract representing the outcome of attempting to insert a single approved track
 * into a destination playlist.
 *
 * @param targetPosition 0-indexed destination position in the playlist
 * @param sourcePosition 0-indexed position in the original source playlist
 * @param sourceTrackId source platform track identifier
 * @param sourceTitle source track title
 * @param sourceArtist source track artist
 * @param destinationTrackId destination video/track identifier (e.g. YouTube video ID)
 * @param destinationPlaylistItemId destination playlist item identifier returned by the provider on success, or null
 * @param status granular insertion outcome status
 * @param message sanitized outcome description or diagnostic message
 */
public record TrackInsertionOutcome(
        int targetPosition,
        int sourcePosition,
        String sourceTrackId,
        String sourceTitle,
        String sourceArtist,
        String destinationTrackId,
        String destinationPlaylistItemId,
        TrackInsertionStatus status,
        String message
) {

    public TrackInsertionOutcome {
        if (targetPosition < 0) {
            throw new IllegalArgumentException("targetPosition must be non-negative: " + targetPosition);
        }
        if (sourcePosition < 0) {
            throw new IllegalArgumentException("sourcePosition must be non-negative: " + sourcePosition);
        }
        Objects.requireNonNull(sourceTrackId, "sourceTrackId must not be null");
        if (sourceTrackId.isBlank()) {
            throw new IllegalArgumentException("sourceTrackId must not be blank");
        }
        Objects.requireNonNull(sourceTitle, "sourceTitle must not be null");
        Objects.requireNonNull(sourceArtist, "sourceArtist must not be null");
        Objects.requireNonNull(destinationTrackId, "destinationTrackId must not be null");
        if (destinationTrackId.isBlank()) {
            throw new IllegalArgumentException("destinationTrackId must not be blank");
        }
        Objects.requireNonNull(status, "status must not be null");
    }

    public static TrackInsertionOutcome success(
            ApprovedTrack track,
            String playlistItemId
    ) {
        Objects.requireNonNull(track, "ApprovedTrack must not be null");
        Objects.requireNonNull(playlistItemId, "playlistItemId must not be null");
        if (playlistItemId.isBlank()) {
            throw new IllegalArgumentException("playlistItemId must not be blank");
        }
        return new TrackInsertionOutcome(
                track.targetPosition(),
                track.sourcePosition(),
                track.sourceTrackId(),
                track.sourceTitle(),
                track.sourceArtist(),
                track.destinationTrackId(),
                playlistItemId.trim(),
                TrackInsertionStatus.SUCCESS,
                "Successfully inserted into destination playlist"
        );
    }

    public static TrackInsertionOutcome failed(
            ApprovedTrack track,
            String errorMessage
    ) {
        Objects.requireNonNull(track, "ApprovedTrack must not be null");
        return new TrackInsertionOutcome(
                track.targetPosition(),
                track.sourcePosition(),
                track.sourceTrackId(),
                track.sourceTitle(),
                track.sourceArtist(),
                track.destinationTrackId(),
                null,
                TrackInsertionStatus.FAILED,
                errorMessage != null && !errorMessage.isBlank() ? errorMessage : "Track insertion failed"
        );
    }

    public static TrackInsertionOutcome uncertain(
            ApprovedTrack track,
            String diagnosticMessage
    ) {
        Objects.requireNonNull(track, "ApprovedTrack must not be null");
        return new TrackInsertionOutcome(
                track.targetPosition(),
                track.sourcePosition(),
                track.sourceTrackId(),
                track.sourceTitle(),
                track.sourceArtist(),
                track.destinationTrackId(),
                null,
                TrackInsertionStatus.UNCERTAIN,
                diagnosticMessage != null && !diagnosticMessage.isBlank()
                        ? diagnosticMessage
                        : "Insertion timed out; remote outcome is uncertain"
        );
    }

    public static TrackInsertionOutcome skipped(
            ApprovedTrack track,
            String reason
    ) {
        Objects.requireNonNull(track, "ApprovedTrack must not be null");
        return new TrackInsertionOutcome(
                track.targetPosition(),
                track.sourcePosition(),
                track.sourceTrackId(),
                track.sourceTitle(),
                track.sourceArtist(),
                track.destinationTrackId(),
                null,
                TrackInsertionStatus.SKIPPED,
                reason != null && !reason.isBlank() ? reason : "Skipped due to prior failure"
        );
    }

    public Optional<String> getDestinationPlaylistItemId() {
        return Optional.ofNullable(destinationPlaylistItemId);
    }

    public Optional<String> getMessage() {
        return Optional.ofNullable(message);
    }

    public boolean isSuccess() {
        return status == TrackInsertionStatus.SUCCESS;
    }

    public boolean isFailed() {
        return status == TrackInsertionStatus.FAILED;
    }

    public boolean isUncertain() {
        return status == TrackInsertionStatus.UNCERTAIN;
    }

    public boolean isSkipped() {
        return status == TrackInsertionStatus.SKIPPED;
    }
}
