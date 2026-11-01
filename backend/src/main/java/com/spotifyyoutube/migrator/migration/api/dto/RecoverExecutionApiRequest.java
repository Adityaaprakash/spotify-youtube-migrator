package com.spotifyyoutube.migrator.migration.api.dto;

import java.util.UUID;

/**
 * Request payload to initiate controlled recovery of a failed migration execution (Phase 11F).
 *
 * @param reviewSessionId optional review session identifier
 * @param targetPosition optional 0-indexed destination position of a specific failed track to recover
 * @param userId optional caller-supplied user ID for anti-impersonation verification
 */
public record RecoverExecutionApiRequest(
        UUID reviewSessionId,
        Integer targetPosition,
        UUID userId
) {
}
