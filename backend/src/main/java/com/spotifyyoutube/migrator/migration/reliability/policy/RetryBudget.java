package com.spotifyyoutube.migrator.migration.reliability.policy;

/**
 * Encapsulates retry attempt counting and budget accounting (Phase 13C).
 * <p>
 * Invariant rules:
 * <ul>
 *   <li>{@code totalAttempts} represents the total attempt budget (initial attempt + retries).</li>
 *   <li>For example, {@code totalAttempts = 3} permits 1 initial attempt and at most 2 retries.</li>
 *   <li>{@code currentAttempt} represents the 1-based attempt index (1 = initial attempt, 2 = 1st retry, etc.).</li>
 *   <li>Immutable and thread-safe value object.</li>
 * </ul>
 */
public record RetryBudget(int currentAttempt, int totalAttempts) {

    public static final int DEFAULT_TOTAL_ATTEMPTS = 3;

    public RetryBudget {
        if (totalAttempts < 1) {
            throw new IllegalArgumentException("totalAttempts must be at least 1, but got: " + totalAttempts);
        }
        if (currentAttempt < 1) {
            throw new IllegalArgumentException("currentAttempt must be at least 1, but got: " + currentAttempt);
        }
    }

    /**
     * Creates an initial retry budget on attempt 1.
     *
     * @param totalAttempts total allowed attempts (including initial attempt)
     * @return initial RetryBudget
     */
    public static RetryBudget initial(int totalAttempts) {
        return new RetryBudget(1, totalAttempts);
    }

    /**
     * Creates a default retry budget (attempt 1 of 3 total attempts).
     *
     * @return default RetryBudget
     */
    public static RetryBudget defaultBudget() {
        return initial(DEFAULT_TOTAL_ATTEMPTS);
    }

    /**
     * Creates a budget for a specific current attempt and total attempts.
     *
     * @param currentAttempt current 1-based attempt number
     * @param totalAttempts total allowed attempts
     * @return RetryBudget
     */
    public static RetryBudget of(int currentAttempt, int totalAttempts) {
        return new RetryBudget(currentAttempt, totalAttempts);
    }

    /**
     * Checks if this is the initial execution attempt (attempt 1).
     *
     * @return true if currentAttempt is 1
     */
    public boolean isInitialAttempt() {
        return currentAttempt == 1;
    }

    /**
     * Checks if further retry attempts remain in the budget.
     *
     * @return true if currentAttempt &lt; totalAttempts
     */
    public boolean hasRemainingAttempts() {
        return currentAttempt < totalAttempts;
    }

    /**
     * Checks if the retry budget has been completely exhausted.
     *
     * @return true if currentAttempt &gt;= totalAttempts
     */
    public boolean isExhausted() {
        return currentAttempt >= totalAttempts;
    }

    /**
     * Returns the number of remaining retry attempts.
     *
     * @return remaining attempts (&gt;= 0)
     */
    public int remainingAttempts() {
        return Math.max(0, totalAttempts - currentAttempt);
    }

    /**
     * Returns a new budget advanced to the next attempt.
     *
     * @return advanced RetryBudget
     */
    public RetryBudget nextAttempt() {
        return new RetryBudget(currentAttempt + 1, totalAttempts);
    }
}
