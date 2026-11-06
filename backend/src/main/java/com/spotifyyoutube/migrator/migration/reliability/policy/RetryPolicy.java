package com.spotifyyoutube.migrator.migration.reliability.policy;

import com.spotifyyoutube.migrator.migration.reliability.classification.ProviderFailureClassification;

/**
 * Evaluates provider failure classifications and determines structured retry decisions (Phase 13C).
 * <p>
 * Decides:
 * <ul>
 *   <li>Whether a failed operation is eligible for retry.</li>
 *   <li>Whether the retry budget has been exhausted.</li>
 *   <li>Which delay should be used before the next attempt (RFC 9110 Retry-After vs Full Jitter).</li>
 *   <li>Whether the delay qualifies for an in-flight micro-retry (&le; 2,000 ms) or requires deferred scheduling.</li>
 *   <li>Whether an ambiguous mutating write requires reconciliation before retry.</li>
 * </ul>
 */
public interface RetryPolicy {

    /**
     * Evaluates a provider failure and returns an immutable retry decision.
     *
     * @param classification structured provider failure classification from Phase 13B
     * @param operationType type of operation evaluated
     * @param budget retry budget reflecting current and total attempts
     * @return immutable retry decision
     */
    RetryDecision evaluate(
            ProviderFailureClassification classification,
            RetryOperationType operationType,
            RetryBudget budget
    );

    /**
     * Convenience overload evaluating a failure with discrete attempt numbers.
     *
     * @param classification structured provider failure classification from Phase 13B
     * @param operationType type of operation evaluated
     * @param currentAttempt 1-based attempt index (1 = initial attempt, 2 = 1st retry, etc.)
     * @param totalAttempts total allowed attempts (including initial attempt)
     * @return immutable retry decision
     */
    default RetryDecision evaluate(
            ProviderFailureClassification classification,
            RetryOperationType operationType,
            int currentAttempt,
            int totalAttempts
    ) {
        return evaluate(classification, operationType, RetryBudget.of(currentAttempt, totalAttempts));
    }
}
