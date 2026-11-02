package com.spotifyyoutube.migrator.migration.api.dto;

import java.util.UUID;

/**
 * REST request body for reconciling uncertain outcomes in a migration execution (Phase 11G).
 */
public record ReconcileExecutionApiRequest(
        UUID userId,
        Integer targetPosition,
        String reason
) {}
