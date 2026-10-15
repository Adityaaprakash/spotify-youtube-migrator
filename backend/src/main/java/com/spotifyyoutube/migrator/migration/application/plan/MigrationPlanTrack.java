package com.spotifyyoutube.migrator.migration.application.plan;

import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Immutable representation of the planned migration treatment of a single source track.
 *
 * @param position 0-based source playlist track index
 * @param sourceTrackId unique source track identifier (e.g. Spotify ID or URI)
 * @param sourceTitle source track title
 * @param sourceArtist source primary artist name
 * @param classification mutually exclusive plan classification
 * @param selectedCandidate chosen destination candidate, or null if unmatched / excluded / failed
 * @param matchDecision Phase 7 matching decision, or null if matching was not performed
 * @param matchScore composite similarity score in [0.0, 1.0], or null if uncomputed
 * @param calibratedProbability estimated probability of true match in [0.0, 1.0], or null if uncalibrated
 * @param matchReasons unmodifiable list of diagnostic notes or decision rationale
 * @param errorMessage sanitized error explanation, or null if no error occurred
 */
public record MigrationPlanTrack(
        int position,
        String sourceTrackId,
        String sourceTitle,
        String sourceArtist,
        PlanTrackClassification classification,
        DestinationTrackCandidate selectedCandidate,
        MatchDecision matchDecision,
        Double matchScore,
        Double calibratedProbability,
        List<String> matchReasons,
        String errorMessage
) {

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)(bearer\\s+|(?:access_token|refresh_token|token|api_key|apikey|key|client_secret|secret|password)=)[^\\s&\"',;]+"
    );

    public MigrationPlanTrack {
        if (position < 0) {
            throw new IllegalArgumentException("position must be non-negative: " + position);
        }
        Objects.requireNonNull(sourceTrackId, "sourceTrackId must not be null");
        if (sourceTrackId.isBlank()) {
            throw new IllegalArgumentException("sourceTrackId must not be blank");
        }
        Objects.requireNonNull(sourceTitle, "sourceTitle must not be null");
        Objects.requireNonNull(sourceArtist, "sourceArtist must not be null");
        Objects.requireNonNull(classification, "classification must not be null");

        if (matchScore != null && (matchScore < 0.0 || matchScore > 1.0)) {
            throw new IllegalArgumentException("matchScore must be between 0.0 and 1.0: " + matchScore);
        }
        if (calibratedProbability != null && (calibratedProbability < 0.0 || calibratedProbability > 1.0)) {
            throw new IllegalArgumentException("calibratedProbability must be between 0.0 and 1.0: " + calibratedProbability);
        }

        matchReasons = matchReasons != null ? List.copyOf(matchReasons) : List.of();
        errorMessage = sanitize(errorMessage);
    }

    public static String sanitize(String message) {
        if (message == null) {
            return null;
        }
        return TOKEN_PATTERN.matcher(message).replaceAll("$1[REDACTED]");
    }

    public Optional<DestinationTrackCandidate> getSelectedCandidate() {
        return Optional.ofNullable(selectedCandidate);
    }

    public Optional<MatchDecision> getMatchDecision() {
        return Optional.ofNullable(matchDecision);
    }

    public Optional<Double> getMatchScore() {
        return Optional.ofNullable(matchScore);
    }

    public Optional<Double> getCalibratedProbability() {
        return Optional.ofNullable(calibratedProbability);
    }

    public Optional<String> getErrorMessage() {
        return Optional.ofNullable(errorMessage);
    }

    public boolean isAutoMatch() {
        return classification == PlanTrackClassification.AUTO_MATCH;
    }

    public boolean isReviewRequired() {
        return classification == PlanTrackClassification.REVIEW_REQUIRED;
    }

    public boolean isUnmatched() {
        return classification == PlanTrackClassification.UNMATCHED;
    }

    public boolean isDuplicate() {
        return classification == PlanTrackClassification.DUPLICATE;
    }

    public boolean isFailed() {
        return classification == PlanTrackClassification.FAILED;
    }

    public boolean isExcluded() {
        return classification == PlanTrackClassification.EXCLUDED;
    }

    public static MigrationPlanTrack autoMatch(
            int position,
            String sourceTrackId,
            String sourceTitle,
            String sourceArtist,
            DestinationTrackCandidate candidate,
            Double matchScore,
            Double calibratedProbability,
            List<String> matchReasons
    ) {
        Objects.requireNonNull(candidate, "candidate must not be null for autoMatch");
        return new MigrationPlanTrack(
                position,
                sourceTrackId,
                sourceTitle,
                sourceArtist,
                PlanTrackClassification.AUTO_MATCH,
                candidate,
                MatchDecision.MATCH,
                matchScore,
                calibratedProbability,
                matchReasons,
                null
        );
    }

    public static MigrationPlanTrack reviewRequired(
            int position,
            String sourceTrackId,
            String sourceTitle,
            String sourceArtist,
            DestinationTrackCandidate candidate,
            Double matchScore,
            Double calibratedProbability,
            List<String> matchReasons
    ) {
        return new MigrationPlanTrack(
                position,
                sourceTrackId,
                sourceTitle,
                sourceArtist,
                PlanTrackClassification.REVIEW_REQUIRED,
                candidate,
                MatchDecision.REVIEW_REQUIRED,
                matchScore,
                calibratedProbability,
                matchReasons,
                null
        );
    }

    public static MigrationPlanTrack duplicate(
            int position,
            String sourceTrackId,
            String sourceTitle,
            String sourceArtist,
            DestinationTrackCandidate candidate,
            Double matchScore,
            Double calibratedProbability,
            String reason
    ) {
        Objects.requireNonNull(candidate, "candidate must not be null for duplicate");
        List<String> reasons = reason != null ? List.of(reason) : List.of();
        return new MigrationPlanTrack(
                position,
                sourceTrackId,
                sourceTitle,
                sourceArtist,
                PlanTrackClassification.DUPLICATE,
                candidate,
                MatchDecision.MATCH,
                matchScore,
                calibratedProbability,
                reasons,
                null
        );
    }

    public static MigrationPlanTrack unmatched(
            int position,
            String sourceTrackId,
            String sourceTitle,
            String sourceArtist,
            List<String> matchReasons
    ) {
        return new MigrationPlanTrack(
                position,
                sourceTrackId,
                sourceTitle,
                sourceArtist,
                PlanTrackClassification.UNMATCHED,
                null,
                MatchDecision.NO_MATCH,
                null,
                null,
                matchReasons,
                null
        );
    }

    public static MigrationPlanTrack failed(
            int position,
            String sourceTrackId,
            String sourceTitle,
            String sourceArtist,
            String errorMessage
    ) {
        return new MigrationPlanTrack(
                position,
                sourceTrackId,
                sourceTitle,
                sourceArtist,
                PlanTrackClassification.FAILED,
                null,
                null,
                null,
                null,
                List.of(),
                errorMessage
        );
    }

    public static MigrationPlanTrack excluded(
            int position,
            String sourceTrackId,
            String sourceTitle,
            String sourceArtist,
            String reason
    ) {
        List<String> reasons = reason != null ? List.of(reason) : List.of();
        return new MigrationPlanTrack(
                position,
                sourceTrackId,
                sourceTitle,
                sourceArtist,
                PlanTrackClassification.EXCLUDED,
                null,
                null,
                null,
                null,
                reasons,
                null
        );
    }
}
