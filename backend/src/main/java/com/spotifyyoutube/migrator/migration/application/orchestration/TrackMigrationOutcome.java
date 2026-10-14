package com.spotifyyoutube.migrator.migration.application.orchestration;

import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.domain.MigrationStatus;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Immutable per-track outcome record capturing the end-to-end migration status,
 * matching decision, similarity score, calibrated probability, and destination association.
 *
 * @param position 0-based source playlist track order
 * @param sourceTrackId source provider track identifier
 * @param sourceTitle normalized source track title
 * @param sourceArtist normalized source primary artist
 * @param destinationTrackId destination provider track/video identifier, or null if unmatched
 * @param destinationTitle destination candidate title, or null if unmatched
 * @param decision matching decision produced by Phase 7 decision engine
 * @param matchScore composite similarity score in [0.0, 1.0], or null if uncomputed
 * @param calibratedProbability calibrated match probability, or null if uncalibrated
 * @param status migration task status (COMPLETED, FAILED, etc.)
 * @param message diagnostic explanation or failure reason
 */
public record TrackMigrationOutcome(
        int position,
        String sourceTrackId,
        String sourceTitle,
        String sourceArtist,
        String destinationTrackId,
        String destinationTitle,
        MatchDecision decision,
        Double matchScore,
        Double calibratedProbability,
        MigrationStatus status,
        String message
) {
    public TrackMigrationOutcome {
        if (position < 0) {
            throw new IllegalArgumentException("position must be non-negative: " + position);
        }
        Objects.requireNonNull(sourceTrackId, "sourceTrackId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    public Optional<String> getDestinationTrackId() {
        return Optional.ofNullable(destinationTrackId);
    }

    public Optional<String> getDestinationTitle() {
        return Optional.ofNullable(destinationTitle);
    }

    public Optional<MatchDecision> getDecision() {
        return Optional.ofNullable(decision);
    }

    public Optional<Double> getMatchScore() {
        return Optional.ofNullable(matchScore);
    }

    public Optional<Double> getCalibratedProbability() {
        return Optional.ofNullable(calibratedProbability);
    }

    public Optional<String> getMessage() {
        return Optional.ofNullable(message);
    }

    public boolean isSuccessful() {
        return status == MigrationStatus.COMPLETED && destinationTrackId != null;
    }

    public boolean isDuplicate() {
        return decision == MatchDecision.MATCH && message != null && message.startsWith("Duplicate");
    }

    public boolean isUnmatched() {
        return decision == MatchDecision.NO_MATCH;
    }

    public boolean isReviewRequired() {
        return decision == MatchDecision.REVIEW_REQUIRED;
    }

    public boolean isProcessingFailure() {
        return status == MigrationStatus.FAILED && (decision == null || (!isDuplicate() && !isUnmatched() && !isReviewRequired()));
    }

    public static TrackMigrationOutcome success(
            int position,
            String sourceTrackId,
            String destinationTrackId,
            MatchDecision decision,
            double matchScore,
            OptionalDouble calibratedProbability
    ) {
        return new TrackMigrationOutcome(
                position,
                sourceTrackId,
                "Source Title",
                "Source Artist",
                destinationTrackId,
                "Destination Title",
                decision,
                matchScore,
                calibratedProbability.isPresent() ? calibratedProbability.getAsDouble() : null,
                MigrationStatus.COMPLETED,
                "Successfully matched and migrated"
        );
    }

    public static TrackMigrationOutcome unmatched(int position, String sourceTrackId, String message) {
        return new TrackMigrationOutcome(
                position,
                sourceTrackId,
                "Source Title",
                "Source Artist",
                null,
                null,
                MatchDecision.NO_MATCH,
                null,
                null,
                MigrationStatus.FAILED,
                message
        );
    }

    public static TrackMigrationOutcome reviewRequired(
            int position,
            String sourceTrackId,
            String destinationTrackId,
            double matchScore,
            OptionalDouble calibratedProbability,
            String message
    ) {
        return new TrackMigrationOutcome(
                position,
                sourceTrackId,
                "Source Title",
                "Source Artist",
                destinationTrackId,
                "Destination Title",
                MatchDecision.REVIEW_REQUIRED,
                matchScore,
                calibratedProbability.isPresent() ? calibratedProbability.getAsDouble() : null,
                MigrationStatus.FAILED,
                message
        );
    }

    public static TrackMigrationOutcome failed(int position, String sourceTrackId, String message) {
        return new TrackMigrationOutcome(
                position,
                sourceTrackId,
                "Source Title",
                "Source Artist",
                null,
                null,
                null,
                null,
                null,
                MigrationStatus.FAILED,
                message
        );
    }
}
