package com.spotifyyoutube.migrator.migration.application.execution.contract;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable value object representing a specific validation failure in an execution request or track selection.
 *
 * @param code structured failure classification
 * @param position 0-indexed track position, or null if failure applies to playlist or session level
 * @param sourceTrackId source track identifier, or null if failure applies to playlist or session level
 * @param message human-readable explanation of why execution is ineligible
 */
public record ExecutionValidationError(
        ValidationFailureCode code,
        Integer position,
        String sourceTrackId,
        String message
) {

    public ExecutionValidationError {
        Objects.requireNonNull(code, "ValidationFailureCode must not be null");
        Objects.requireNonNull(message, "Error message must not be null");
        if (message.isBlank()) {
            throw new IllegalArgumentException("Error message must not be blank");
        }
    }

    public static ExecutionValidationError sessionLevel(ValidationFailureCode code, String message) {
        return new ExecutionValidationError(code, null, null, message);
    }

    public static ExecutionValidationError trackLevel(ValidationFailureCode code, int position, String sourceTrackId, String message) {
        return new ExecutionValidationError(code, position, sourceTrackId, message);
    }

    public Optional<Integer> getPosition() {
        return Optional.ofNullable(position);
    }

    public Optional<String> getSourceTrackId() {
        return Optional.ofNullable(sourceTrackId);
    }

    public boolean isTrackLevel() {
        return position != null;
    }
}
