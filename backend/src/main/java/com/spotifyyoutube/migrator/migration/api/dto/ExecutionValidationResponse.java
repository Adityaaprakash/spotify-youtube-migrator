package com.spotifyyoutube.migrator.migration.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Top-level API response from execution validation endpoints.
 *
 * @param valid true if migration is eligible for execution, false otherwise
 * @param reviewSessionId unique review session identifier
 * @param approvedContext validated context if valid, null otherwise
 * @param errors list of validation errors if invalid
 */
public record ExecutionValidationResponse(
        boolean valid,
        UUID reviewSessionId,
        ApprovedMigrationContextResponse approvedContext,
        List<ExecutionValidationErrorResponse> errors
) {
}
