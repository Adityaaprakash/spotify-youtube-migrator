package com.spotifyyoutube.migrator.migration.application.execution.failure;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable analysis result produced by {@link ExecutionFailureClassifier} (Phase 11F).
 *
 * @param classification structured failure classification
 * @param reason human-readable diagnostic reason (sanitized)
 * @param retryable whether the operation is safe and eligible for automatic or user retry
 * @param requiresManualIntervention whether human review or manual credential intervention is required
 * @param providerStatusCode upstream provider HTTP status code, if available
 */
public record ExecutionFailureAnalysis(
        ExecutionFailureClassification classification,
        String reason,
        boolean retryable,
        boolean requiresManualIntervention,
        Integer providerStatusCode
) {

    public ExecutionFailureAnalysis {
        Objects.requireNonNull(classification, "classification must not be null");
        reason = reason != null ? reason : "Unknown failure";
    }

    public Optional<Integer> getProviderStatusCode() {
        return Optional.ofNullable(providerStatusCode);
    }
}
