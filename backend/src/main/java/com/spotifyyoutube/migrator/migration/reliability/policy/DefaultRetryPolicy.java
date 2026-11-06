package com.spotifyyoutube.migrator.migration.reliability.policy;

import com.spotifyyoutube.migrator.migration.reliability.classification.ProviderFailureCategory;
import com.spotifyyoutube.migrator.migration.reliability.classification.ProviderFailureClassification;
import com.spotifyyoutube.migrator.migration.reliability.classification.RetryEligibility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * Standard implementation of {@link RetryPolicy} (Phase 13C).
 * <p>
 * Decides retry authorization, delay calculation (RFC 9110 Retry-After vs AWS Full Jitter),
 * execution tier (strict 2.0-second in-flight boundary), and ambiguous mutating write protection
 * according to Phase 13A architecture (ADR-031).
 * <p>
 * Invariant rules:
 * <ul>
 *   <li>Consumes {@link ProviderFailureClassification} from Phase 13B; does not duplicate classification logic.</li>
 *   <li>Never silently truncates or shortens a valid provider-specified {@code Retry-After} delay.</li>
 *   <li>Enforces the strict 2,000 ms in-flight micro-retry boundary; delays &gt; 2,000 ms or subsequent attempts release the worker thread.</li>
 *   <li>Rejects blind duplicate external mutations upon ambiguous transport failures (timeouts, 5xx) on mutating operations.</li>
 *   <li>Defers daily quota exhaustion ({@code quotaExceeded}) without retrying in the current quota window.</li>
 *   <li>Thread-safe, deterministic, and independently unit-testable.</li>
 * </ul>
 */
@Component
public class DefaultRetryPolicy implements RetryPolicy {

    public static final Duration DEFAULT_IN_FLIGHT_THRESHOLD = Duration.ofSeconds(2);
    public static final Duration DEFAULT_MAX_EXCESSIVE_DELAY = Duration.ofHours(24);
    public static final Duration DEFAULT_QUOTA_DEFERRAL = Duration.ofHours(1);

    private final BackoffCalculator backoffCalculator;
    private final Duration inFlightThreshold;
    private final Duration maxExcessiveDelay;
    private final Duration defaultQuotaDeferral;

    /**
     * Constructs a default retry policy with standard backoff calculator and defaults.
     */
    public DefaultRetryPolicy() {
        this(new BackoffCalculator());
    }

    /**
     * Constructs a retry policy with a custom backoff calculator and standard thresholds.
     *
     * @param backoffCalculator backoff calculation engine
     */
    @Autowired
    public DefaultRetryPolicy(BackoffCalculator backoffCalculator) {
        this(backoffCalculator, DEFAULT_IN_FLIGHT_THRESHOLD, DEFAULT_MAX_EXCESSIVE_DELAY, DEFAULT_QUOTA_DEFERRAL);
    }

    /**
     * Constructs a fully-configured retry policy.
     *
     * @param backoffCalculator backoff calculation engine
     * @param inFlightThreshold maximum delay eligible for in-flight micro-retry (default 2s)
     * @param maxExcessiveDelay maximum delay ceiling before escalation (default 24h)
     * @param defaultQuotaDeferral fallback deferral duration when quota is exceeded without Retry-After (default 1h)
     */
    public DefaultRetryPolicy(
            BackoffCalculator backoffCalculator,
            Duration inFlightThreshold,
            Duration maxExcessiveDelay,
            Duration defaultQuotaDeferral
    ) {
        this.backoffCalculator = Objects.requireNonNull(backoffCalculator, "backoffCalculator must not be null");
        this.inFlightThreshold = Objects.requireNonNull(inFlightThreshold, "inFlightThreshold must not be null");
        this.maxExcessiveDelay = Objects.requireNonNull(maxExcessiveDelay, "maxExcessiveDelay must not be null");
        this.defaultQuotaDeferral = Objects.requireNonNull(defaultQuotaDeferral, "defaultQuotaDeferral must not be null");

        if (inFlightThreshold.isNegative()) {
            throw new IllegalArgumentException("inFlightThreshold must not be negative");
        }
        if (maxExcessiveDelay.isNegative() || maxExcessiveDelay.isZero()) {
            throw new IllegalArgumentException("maxExcessiveDelay must be positive");
        }
        if (defaultQuotaDeferral.isNegative() || defaultQuotaDeferral.isZero()) {
            throw new IllegalArgumentException("defaultQuotaDeferral must be positive");
        }
    }

    @Override
    public RetryDecision evaluate(
            ProviderFailureClassification classification,
            RetryOperationType operationType,
            RetryBudget budget
    ) {
        // 1. Safe defensive defaults
        if (classification == null) {
            return RetryDecision.nonRetryable(
                    operationType != null ? operationType : RetryOperationType.PROVIDER_READ,
                    budget != null ? budget : RetryBudget.defaultBudget(),
                    "ProviderFailureClassification must not be null; failing safely as non-retryable"
            );
        }
        if (operationType == null) {
            return RetryDecision.nonRetryable(
                    RetryOperationType.PROVIDER_READ,
                    budget != null ? budget : RetryBudget.defaultBudget(),
                    "RetryOperationType must not be null; failing safely as non-retryable"
            );
        }
        if (budget == null) {
            return RetryDecision.nonRetryable(
                    operationType,
                    RetryBudget.defaultBudget(),
                    "RetryBudget must not be null; failing safely as non-retryable"
            );
        }

        RetryEligibility eligibility = classification.retryEligibility();

        // 2. Permanent non-retryable failures
        if (eligibility == RetryEligibility.NON_RETRYABLE) {
            return RetryDecision.nonRetryable(
                    operationType,
                    budget,
                    "Operation is permanently non-retryable: " + classification.category() + " (" + classification.diagnosticMessage() + ")"
            );
        }

        // 3. Re-authentication / token expiration failures
        if (eligibility == RetryEligibility.REQUIRES_REAUTHORIZATION) {
            return RetryDecision.requiresReauthorization(
                    operationType,
                    budget,
                    "Provider authentication failed or token expired (HTTP 401); requires reauthorization before subsequent attempt"
            );
        }

        // 4. Daily quota exhaustion (DEFERRED_RESET)
        if (eligibility == RetryEligibility.DEFERRED_RESET) {
            Duration quotaDelay = classification.retryAfter().orElse(defaultQuotaDeferral);
            DelaySource delaySource = classification.retryAfter().isPresent()
                    ? DelaySource.PROVIDER_RETRY_AFTER
                    : DelaySource.NONE;

            return RetryDecision.deferredQuotaReset(
                    operationType,
                    budget,
                    quotaDelay,
                    delaySource,
                    "Provider daily quota exhausted (HTTP 403 quotaExceeded); deferred until quota reset window without retrying in current window"
            );
        }

        // 5. Unrecognized eligibility fails safely
        if (eligibility != RetryEligibility.RETRYABLE) {
            return RetryDecision.nonRetryable(
                    operationType,
                    budget,
                    "Unrecognized retry eligibility [" + eligibility + "]; failing safely as non-retryable"
            );
        }

        // 6. Check attempt budget
        if (budget.isExhausted()) {
            return RetryDecision.budgetExhausted(
                    operationType,
                    budget,
                    "Retry budget exhausted: attempt " + budget.currentAttempt() + " of " + budget.totalAttempts() + " total attempts"
            );
        }

        // 7. Ambiguous external mutating write safety check (Section 7)
        if (operationType.isMutating() && isAmbiguousTransportFailure(classification)) {
            return RetryDecision.reconciliationRequired(
                    operationType,
                    budget,
                    "Mutating operation " + operationType + " encountered ambiguous transport error (" + classification.category()
                            + "); reconciliation required before retry to prevent duplicate mutations"
            );
        }

        // 8. Determine effective delay (Section 5)
        Duration delay;
        DelaySource delaySource;

        if (classification.retryAfter().isPresent()) {
            // Provider hint takes absolute precedence; NEVER shorten using local cap
            delay = classification.retryAfter().get();
            delaySource = DelaySource.PROVIDER_RETRY_AFTER;
        } else {
            // Calculate Full Jitter backoff
            delay = backoffCalculator.calculateBackoff(budget.currentAttempt());
            delaySource = DelaySource.FULL_JITTER_BACKOFF;
        }

        // 9. Two-tier execution strategy (Section 6)
        boolean withinInFlightThreshold = delay.compareTo(inFlightThreshold) <= 0;
        boolean isFirstRetryAttempt = budget.isInitialAttempt();

        if (withinInFlightThreshold && isFirstRetryAttempt) {
            return RetryDecision.retryInFlight(
                    operationType,
                    budget,
                    delay,
                    delaySource,
                    "In-flight micro-retry permitted: delay " + delay.toMillis() + " ms <= "
                            + inFlightThreshold.toMillis() + " ms threshold on initial attempt retry"
            );
        } else {
            String deferReason;
            if (!withinInFlightThreshold) {
                deferReason = "Deferred execution required: delay " + delay.toMillis() + " ms exceeds "
                        + inFlightThreshold.toMillis() + " ms in-flight threshold";
            } else {
                deferReason = "Deferred execution required: maximum 1 in-flight micro-retry permitted; attempt "
                        + budget.currentAttempt() + " must be durably scheduled";
            }

            if (delay.compareTo(maxExcessiveDelay) > 0) {
                deferReason = "Deferred execution required: provider delay exceeds excessive delay threshold ("
                        + maxExcessiveDelay.toHours() + "h)";
            }

            return RetryDecision.retryDeferred(
                    operationType,
                    budget,
                    delay,
                    delaySource,
                    deferReason
            );
        }
    }

    private boolean isAmbiguousTransportFailure(ProviderFailureClassification classification) {
        // Rate-limits (429 or user-rate-limit 403) are rejected BEFORE execution, so they are not ambiguous.
        if (classification.isRateLimited()) {
            return false;
        }

        // Server errors (500, 502, 503) or network socket timeouts during mutation leave the remote outcome uncertain.
        if (classification.isTransientServerError()) {
            return true;
        }

        String reason = classification.providerReason();
        if (reason != null) {
            String lower = reason.toLowerCase();
            if (lower.contains("timeout") || lower.contains("connection") || lower.contains("network")) {
                return true;
            }
        }

        String message = classification.diagnosticMessage();
        if (message != null) {
            String lower = message.toLowerCase();
            return lower.contains("timeout") || lower.contains("connection") || lower.contains("resourceaccessexception");
        }

        return false;
    }

    public BackoffCalculator getBackoffCalculator() {
        return backoffCalculator;
    }

    public Duration getInFlightThreshold() {
        return inFlightThreshold;
    }

    public Duration getMaxExcessiveDelay() {
        return maxExcessiveDelay;
    }

    public Duration getDefaultQuotaDeferral() {
        return defaultQuotaDeferral;
    }
}
