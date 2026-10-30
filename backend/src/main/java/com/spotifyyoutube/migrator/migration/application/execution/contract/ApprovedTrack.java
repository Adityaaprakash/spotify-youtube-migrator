package com.spotifyyoutube.migrator.migration.application.execution.contract;

import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable domain contract representing a single approved track ready for destination insertion.
 * <p>
 * Binds the original source track identity and ordering to the validated destination identity,
 * preserving provenance (automatic vs user-selected) and establishing deterministic destination ordering.
 *
 * @param sourcePosition 0-indexed track position in the original source playlist
 * @param sourceTrackId source track identifier (e.g. Spotify track ID or URI)
 * @param sourceTitle source track title
 * @param sourceArtist source track primary artist
 * @param destinationTrackId validated destination video/track identifier (e.g. YouTube video ID)
 * @param destinationTitle destination video title, if available
 * @param destinationChannel destination channel or uploader name, if available
 * @param destinationDuration destination track duration, if available
 * @param decisionSource provenance of the decision (AUTOMATIC vs USER_SELECTED)
 * @param targetPosition 0-indexed sequential position in the destination playlist
 */
public record ApprovedTrack(
        int sourcePosition,
        String sourceTrackId,
        String sourceTitle,
        String sourceArtist,
        String destinationTrackId,
        String destinationTitle,
        String destinationChannel,
        Duration destinationDuration,
        ReviewDecisionSource decisionSource,
        int targetPosition
) {

    public ApprovedTrack {
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
        if (destinationDuration != null && destinationDuration.isNegative()) {
            throw new IllegalArgumentException("destinationDuration must not be negative: " + destinationDuration);
        }
        Objects.requireNonNull(decisionSource, "decisionSource must not be null");
        if (targetPosition < 0) {
            throw new IllegalArgumentException("targetPosition must be non-negative: " + targetPosition);
        }
    }

    public Optional<String> getDestinationTitle() {
        return Optional.ofNullable(destinationTitle);
    }

    public Optional<String> getDestinationChannel() {
        return Optional.ofNullable(destinationChannel);
    }

    public Optional<Duration> getDestinationDuration() {
        return Optional.ofNullable(destinationDuration);
    }

    public boolean isUserSelected() {
        return decisionSource == ReviewDecisionSource.USER_SELECTED;
    }

    public boolean isAutomatic() {
        return decisionSource == ReviewDecisionSource.AUTOMATIC;
    }
}
