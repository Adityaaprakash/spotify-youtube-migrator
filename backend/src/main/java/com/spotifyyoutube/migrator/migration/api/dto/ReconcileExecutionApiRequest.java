package com.spotifyyoutube.migrator.migration.api.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * REST request body for reconciling uncertain outcomes in a migration execution (Phase 11G).
 */
public record ReconcileExecutionApiRequest(
        UUID userId,
        Integer targetPosition,
        @Size(max = 1000, message = "Reconciliation reason cannot exceed 1000 characters")
        String reason
) {}
