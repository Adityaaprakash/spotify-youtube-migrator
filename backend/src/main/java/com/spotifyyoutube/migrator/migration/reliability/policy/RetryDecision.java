package com.spotifyyoutube.migrator.migration.reliability.policy;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable decision outcome produced by {@link RetryPolicy} (Phase 13C).
 * <p>
 * Encapsulates whether a retry is authorized, the execution tier (in-flight vs deferred),
 * the calculated delay, delay provenance, and diagnostic rationale.
 *
 * @param decisionType structured decision outcome
 * @param operationType type of operation evaluated
 * @param budget retry budget reflecting current and total attempts
 * @param delay effective delay duration before next attempt (ZERO if not retryable)
 * @param delaySource provenance of the delay (PROVIDER_RETRY_AFTER, FULL_JITTER_BACKOFF, or NONE)
 * @param executionTier execution strategy (IN_FLIGHT, DEFERRED, or NONE)
 * @param reason human-readable diagnostic explanation of the decision
 */
public record RetryDecision(
        RetryDecisionType decisionType,
        RetryOperationType operationType,
        RetryBudget budget,
        Duration delay,
        DelaySource delaySource,
        ExecutionTier executionTier,
        String reason
) {

    public RetryDecision {
        Objects.requireNonNull(decisionType, "decisionType must not be null");
        Objects.requireNonNull(operationType, "operationType must not be null");
        Objects.requireNonNull(budget, "budget must not be null");
        Objects.requireNonNull(delay, "delay must not be null");
        Objects.requireNonNull(delaySource, "delaySource must not be null");
        Objects.requireNonNull(executionTier, "executionTier must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }

    /**
     * Checks if a retry attempt is authorized.
     *
     * @return true if decisionType is RETRY_PERMITTED
     */
    public boolean isRetryPermitted() {
        return decisionType == RetryDecisionType.RETRY_PERMITTED;
    }

    /**
     * Checks if this retry qualifies as an in-flight micro-retry (&le; 2,000 ms, attempt 1).
     *
     * @return true if executionTier is IN_FLIGHT
     */
    public boolean isInFlight() {
        return executionTier == ExecutionTier.IN_FLIGHT;
    }

    /**
     * Checks if this operation must release the worker thread and be scheduled durably.
     *
     * @return true if executionTier is DEFERRED
     */
    public boolean isDeferred() {
        return executionTier == ExecutionTier.DEFERRED;
    }

    /**
     * Checks if retry was rejected because the attempt budget was exhausted.
     *
     * @return true if decisionType is BUDGET_EXHAUSTED
     */
    public boolean isBudgetExhausted() {
        return decisionType == RetryDecisionType.BUDGET_EXHAUSTED;
    }

    /**
     * Checks if the mutating write outcome is ambiguous and requires remote reconciliation.
     *
     * @return true if decisionType is RECONCILIATION_REQUIRED
     */
    public boolean isReconciliationRequired() {
        return decisionType == RetryDecisionType.RECONCILIATION_REQUIRED;
    }

    /**
     * Checks if retry is deferred due to provider daily quota exhaustion.
     *
     * @return true if decisionType is DEFERRED_QUOTA_RESET
     */
    public boolean isQuotaDeferred() {
        return decisionType == RetryDecisionType.DEFERRED_QUOTA_RESET;
    }

    /**
     * Checks if the error is permanently non-retryable.
     *
     * @return true if decisionType is NON_RETRYABLE
     */
    public boolean isNonRetryable() {
        return decisionType == RetryDecisionType.NON_RETRYABLE;
    }

    /**
     * Checks if re-authentication / token refresh is required before any subsequent attempt.
     *
     * @return true if decisionType is REQUIRES_REAUTHORIZATION
     */
    public boolean isRequiresReauthorization() {
        return decisionType == RetryDecisionType.REQUIRES_REAUTHORIZATION;
    }

    // Factory methods

    public static RetryDecision retryInFlight(
            RetryOperationType operationType,
            RetryBudget budget,
            Duration delay,
            DelaySource delaySource,
            String reason
    ) {
        return new RetryDecision(
                RetryDecisionType.RETRY_PERMITTED,
                operationType,
                budget,
                delay,
                delaySource,
                ExecutionTier.IN_FLIGHT,
                reason
        );
    }

    public static RetryDecision retryDeferred(
            RetryOperationType operationType,
            RetryBudget budget,
            Duration delay,
            DelaySource delaySource,
            String reason
    ) {
        return new RetryDecision(
                RetryDecisionType.RETRY_PERMITTED,
                operationType,
                budget,
                delay,
                delaySource,
                ExecutionTier.DEFERRED,
                reason
        );
    }

    public static RetryDecision deferredQuotaReset(
            RetryOperationType operationType,
            RetryBudget budget,
            Duration delay,
            DelaySource delaySource,
            String reason
    ) {
        return new RetryDecision(
                RetryDecisionType.DEFERRED_QUOTA_RESET,
                operationType,
                budget,
                delay,
                delaySource,
                ExecutionTier.DEFERRED,
                reason
        );
    }

    public static RetryDecision reconciliationRequired(
            RetryOperationType operationType,
            RetryBudget budget,
            String reason
    ) {
        return new RetryDecision(
                RetryDecisionType.RECONCILIATION_REQUIRED,
                operationType,
                budget,
                Duration.ZERO,
                DelaySource.NONE,
                ExecutionTier.DEFERRED,
                reason
        );
    }

    public static RetryDecision budgetExhausted(
            RetryOperationType operationType,
            RetryBudget budget,
            String reason
    ) {
        return new RetryDecision(
                RetryDecisionType.BUDGET_EXHAUSTED,
                operationType,
                budget,
                Duration.ZERO,
                DelaySource.NONE,
                ExecutionTier.NONE,
                reason
        );
    }

    public static RetryDecision nonRetryable(
            RetryOperationType operationType,
            RetryBudget budget,
            String reason
    ) {
        return new RetryDecision(
                RetryDecisionType.NON_RETRYABLE,
                operationType,
                budget,
                Duration.ZERO,
                DelaySource.NONE,
                ExecutionTier.NONE,
                reason
        );
    }

    public static RetryDecision requiresReauthorization(
            RetryOperationType operationType,
            RetryBudget budget,
            String reason
    ) {
        return new RetryDecision(
                RetryDecisionType.REQUIRES_REAUTHORIZATION,
                operationType,
                budget,
                Duration.ZERO,
                DelaySource.NONE,
                ExecutionTier.NONE,
                reason
        );
    }
}
