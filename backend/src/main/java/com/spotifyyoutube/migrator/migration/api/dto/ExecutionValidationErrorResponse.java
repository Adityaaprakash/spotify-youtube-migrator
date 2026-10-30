package com.spotifyyoutube.migrator.migration.api.dto;

/**
 * API response representing a single execution validation error.
 *
 * @param code structured error code
 * @param position 0-indexed track position, or null if session-level
 * @param sourceTrackId source track identifier, or null if session-level
 * @param message human-readable error explanation
 */
public record ExecutionValidationErrorResponse(
        String code,
        Integer position,
        String sourceTrackId,
        String message
) {
}
