package com.spotifyyoutube.migrator.migration.reliability.policy;

import com.spotifyyoutube.migrator.migration.reliability.classification.ProviderFailureCategory;
import com.spotifyyoutube.migrator.migration.reliability.classification.ProviderFailureClassification;
import com.spotifyyoutube.migrator.migration.reliability.classification.RetryEligibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 13C: RetryPolicy Unit Tests")
class RetryPolicyTest {

    private BackoffCalculator deterministicBackoff;
    private DefaultRetryPolicy policy;

    @BeforeEach
    void setUp() {
        // Base delay 500 ms, max 30 s, deterministic random = 1.0 (returns ceiling)
        deterministicBackoff = new BackoffCalculator(
                Duration.ofMillis(500),
                Duration.ofSeconds(30),
                () -> 1.0
        );
        policy = new DefaultRetryPolicy(deterministicBackoff);
    }

    @Nested
    @DisplayName("1. Retry Eligibility & Budget Evaluation")
    class EligibilityAndBudgetTests {

        @Test
        @DisplayName("Authorizes retry when failure is RETRYABLE and budget remains")
        void authorizesRetryableWithBudget() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.RATE_LIMITED,
                    429,
                    "rateLimitExceeded",
                    RetryEligibility.RETRYABLE,
                    Optional.empty(),
                    "Rate limited"
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.PROVIDER_READ,
                    RetryBudget.initial(3) // attempt 1 of 3
            );

            assertThat(decision.isRetryPermitted()).isTrue();
            assertThat(decision.decisionType()).isEqualTo(RetryDecisionType.RETRY_PERMITTED);
            assertThat(decision.delaySource()).isEqualTo(DelaySource.FULL_JITTER_BACKOFF);
            assertThat(decision.delay()).isEqualTo(Duration.ofMillis(1000)); // 500ms * 2^1 = 1000ms
        }

        @Test
        @DisplayName("Rejects retry and marks BUDGET_EXHAUSTED when attempt budget is spent")
        void rejectsWhenBudgetExhausted() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.RATE_LIMITED,
                    429,
                    "rateLimitExceeded",
                    RetryEligibility.RETRYABLE,
                    Optional.empty(),
                    "Rate limited"
            );

            // Attempt 3 of 3 -> budget exhausted
            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.PROVIDER_READ,
                    RetryBudget.of(3, 3)
            );

            assertThat(decision.isRetryPermitted()).isFalse();
            assertThat(decision.isBudgetExhausted()).isTrue();
            assertThat(decision.decisionType()).isEqualTo(RetryDecisionType.BUDGET_EXHAUSTED);
            assertThat(decision.executionTier()).isEqualTo(ExecutionTier.NONE);
            assertThat(decision.delay()).isEqualTo(Duration.ZERO);
            assertThat(decision.delaySource()).isEqualTo(DelaySource.NONE);
            assertThat(decision.reason()).contains("Retry budget exhausted");
        }

        @Test
        @DisplayName("Rejects retry for NON_RETRYABLE failure categories (e.g. 400 Bad Request)")
        void rejectsNonRetryableFailures() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.INVALID_REQUEST,
                    400,
                    "badRequest",
                    RetryEligibility.NON_RETRYABLE,
                    Optional.empty(),
                    "Malformed payload"
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.PROVIDER_READ,
                    RetryBudget.initial(3)
            );

            assertThat(decision.isRetryPermitted()).isFalse();
            assertThat(decision.isNonRetryable()).isTrue();
            assertThat(decision.decisionType()).isEqualTo(RetryDecisionType.NON_RETRYABLE);
            assertThat(decision.executionTier()).isEqualTo(ExecutionTier.NONE);
            assertThat(decision.delay()).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("Rejects automatic retry and signals REQUIRES_REAUTHORIZATION for auth failures (401)")
        void rejectsAuthFailuresRequiringReauthorization() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.AUTH_EXPIRED,
                    401,
                    "authError",
                    RetryEligibility.REQUIRES_REAUTHORIZATION,
                    Optional.empty(),
                    "Invalid token"
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.PROVIDER_READ,
                    RetryBudget.initial(3)
            );

            assertThat(decision.isRetryPermitted()).isFalse();
            assertThat(decision.isRequiresReauthorization()).isTrue();
            assertThat(decision.decisionType()).isEqualTo(RetryDecisionType.REQUIRES_REAUTHORIZATION);
            assertThat(decision.executionTier()).isEqualTo(ExecutionTier.NONE);
            assertThat(decision.delay()).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("Defers quota exhaustion (DEFERRED_RESET) with provider delay without retrying in current window")
        void defersQuotaExhaustionWithProviderDelay() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.QUOTA_EXCEEDED,
                    403,
                    "quotaExceeded",
                    RetryEligibility.DEFERRED_RESET,
                    Optional.of(Duration.ofHours(5)),
                    "Daily quota exceeded"
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.DESTINATION_PLAYLIST_CREATION,
                    RetryBudget.initial(3)
            );

            assertThat(decision.isRetryPermitted()).isFalse();
            assertThat(decision.isQuotaDeferred()).isTrue();
            assertThat(decision.isDeferred()).isTrue();
            assertThat(decision.decisionType()).isEqualTo(RetryDecisionType.DEFERRED_QUOTA_RESET);
            assertThat(decision.executionTier()).isEqualTo(ExecutionTier.DEFERRED);
            assertThat(decision.delay()).isEqualTo(Duration.ofHours(5));
            assertThat(decision.hasKnownDelay()).isTrue();
            assertThat(decision.delayOptional()).contains(Duration.ofHours(5));
            assertThat(decision.delaySource()).isEqualTo(DelaySource.PROVIDER_RETRY_AFTER);
        }

        @Test
        @DisplayName("Defers quota exhaustion without fabricating reset timestamp when provider delay is missing")
        void defersQuotaExhaustionWithoutFabricatingResetTimestamp() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.QUOTA_EXCEEDED,
                    403,
                    "quotaExceeded",
                    RetryEligibility.DEFERRED_RESET,
                    Optional.empty(),
                    "Daily quota exceeded"
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.TRACK_INSERTION,
                    RetryBudget.initial(3)
            );

            assertThat(decision.isRetryPermitted()).isFalse();
            assertThat(decision.isQuotaDeferred()).isTrue();
            assertThat(decision.isDeferred()).isTrue();
            assertThat(decision.decisionType()).isEqualTo(RetryDecisionType.DEFERRED_QUOTA_RESET);
            assertThat(decision.executionTier()).isEqualTo(ExecutionTier.DEFERRED);
            assertThat(decision.delay()).isNull();
            assertThat(decision.hasKnownDelay()).isFalse();
            assertThat(decision.delayOptional()).isEmpty();
            assertThat(decision.delaySource()).isEqualTo(DelaySource.NONE);
            assertThat(decision.reason()).contains("quota reset time is unknown and must not be retried in current quota window");
        }

        @Test
        @DisplayName("Fails safely as NON_RETRYABLE for UNKNOWN classification or null input")
        void failsSafelyForUnknownOrNull() {
            ProviderFailureClassification unknown = new ProviderFailureClassification(
                    ProviderFailureCategory.UNKNOWN,
                    null,
                    null,
                    RetryEligibility.NON_RETRYABLE,
                    Optional.empty(),
                    "Unknown exception"
            );

            RetryDecision d1 = policy.evaluate(unknown, RetryOperationType.PROVIDER_READ, RetryBudget.initial(3));
            assertThat(d1.isRetryPermitted()).isFalse();
            assertThat(d1.isNonRetryable()).isTrue();

            RetryDecision d2 = policy.evaluate(null, RetryOperationType.PROVIDER_READ, RetryBudget.initial(3));
            assertThat(d2.isRetryPermitted()).isFalse();
            assertThat(d2.isNonRetryable()).isTrue();
        }
    }

    @Nested
    @DisplayName("2. Retry-After Precedence & Delay Calculations")
    class RetryAfterPrecedenceTests {

        @Test
        @DisplayName("Respects valid provider Retry-After over local exponential backoff")
        void respectsProviderRetryAfter() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.RATE_LIMITED,
                    429,
                    "rateLimitExceeded",
                    RetryEligibility.RETRYABLE,
                    Optional.of(Duration.ofSeconds(15)),
                    "Rate limit"
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.PROVIDER_READ,
                    RetryBudget.initial(3)
            );

            assertThat(decision.isRetryPermitted()).isTrue();
            assertThat(decision.delay()).isEqualTo(Duration.ofSeconds(15));
            assertThat(decision.delaySource()).isEqualTo(DelaySource.PROVIDER_RETRY_AFTER);
        }

        @Test
        @DisplayName("Never shortens provider Retry-After even if it exceeds local maxDelay cap")
        void neverShortensProviderDelay() {
            // Local cap is 30 seconds; provider specifies 120 seconds
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.RATE_LIMITED,
                    429,
                    "rateLimitExceeded",
                    RetryEligibility.RETRYABLE,
                    Optional.of(Duration.ofSeconds(120)),
                    "Rate limit"
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.PROVIDER_READ,
                    RetryBudget.initial(3)
            );

            assertThat(decision.isRetryPermitted()).isTrue();
            assertThat(decision.delay()).isEqualTo(Duration.ofSeconds(120));
            assertThat(decision.delaySource()).isEqualTo(DelaySource.PROVIDER_RETRY_AFTER);
        }

        @Test
        @DisplayName("Falls back to Full Jitter when Retry-After is absent or invalid")
        void fallsBackToFullJitterWhenRetryAfterAbsent() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.USER_RATE_LIMITED,
                    403,
                    "userRateLimitExceeded",
                    RetryEligibility.RETRYABLE,
                    Optional.empty(),
                    "User rate limit"
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.PROVIDER_READ,
                    RetryBudget.initial(3) // attempt 1: 500ms * 2^1 = 1000ms
            );

            assertThat(decision.isRetryPermitted()).isTrue();
            assertThat(decision.delay()).isEqualTo(Duration.ofMillis(1000));
            assertThat(decision.delaySource()).isEqualTo(DelaySource.FULL_JITTER_BACKOFF);
        }

        @Test
        @DisplayName("Accepts zero delay for past HTTP-dates without error")
        void handlesZeroDelayFromPastDate() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.RATE_LIMITED,
                    429,
                    "rateLimitExceeded",
                    RetryEligibility.RETRYABLE,
                    Optional.of(Duration.ZERO),
                    "Past date"
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.PROVIDER_READ,
                    RetryBudget.initial(3)
            );

            assertThat(decision.isRetryPermitted()).isTrue();
            assertThat(decision.delay()).isEqualTo(Duration.ZERO);
            assertThat(decision.isInFlight()).isTrue();
        }

        @Test
        @DisplayName("Escalates and rejects normal retry when provider delay exceeds maximum excessive ceiling")
        void excessiveProviderDelayRequiresEscalation() {
            // 48 hours exceeds the 24 hour excessive threshold
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.RATE_LIMITED,
                    429,
                    "rateLimitExceeded",
                    RetryEligibility.RETRYABLE,
                    Optional.of(Duration.ofHours(48)),
                    "Excessive delay"
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.PROVIDER_READ,
                    RetryBudget.initial(3)
            );

            assertThat(decision.isRetryPermitted()).isFalse();
            assertThat(decision.isExcessiveDelay()).isTrue();
            assertThat(decision.isEscalationRequired()).isTrue();
            assertThat(decision.decisionType()).isEqualTo(RetryDecisionType.EXCESSIVE_DELAY);
            assertThat(decision.isDeferred()).isTrue();
            assertThat(decision.executionTier()).isEqualTo(ExecutionTier.DEFERRED);
            assertThat(decision.delay()).isEqualTo(Duration.ofHours(48)); // Unshortened!
            assertThat(decision.delaySource()).isEqualTo(DelaySource.PROVIDER_RETRY_AFTER);
            assertThat(decision.reason()).contains("excessive delay threshold");
        }
    }


    @Nested
    @DisplayName("3. Two-Tier Execution Strategy (2.0s Boundary)")
    class TwoTierExecutionTests {

        @Test
        @DisplayName("Assigns IN_FLIGHT when delay <= 2,000 ms on first retry attempt")
        void inFlightWhenUnderTwoSecondsOnFirstAttempt() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.USER_RATE_LIMITED,
                    403,
                    "userRateLimitExceeded",
                    RetryEligibility.RETRYABLE,
                    Optional.of(Duration.ofMillis(1500)),
                    "Short delay"
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.PROVIDER_READ,
                    RetryBudget.initial(3) // attempt 1
            );

            assertThat(decision.isRetryPermitted()).isTrue();
            assertThat(decision.isInFlight()).isTrue();
            assertThat(decision.isDeferred()).isFalse();
            assertThat(decision.executionTier()).isEqualTo(ExecutionTier.IN_FLIGHT);
        }

        @Test
        @DisplayName("Assigns IN_FLIGHT when delay exactly equals 2,000 ms on first retry attempt")
        void inFlightWhenExactlyTwoSeconds() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.USER_RATE_LIMITED,
                    403,
                    "userRateLimitExceeded",
                    RetryEligibility.RETRYABLE,
                    Optional.of(Duration.ofMillis(2000)),
                    "Exact 2s delay"
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.PROVIDER_READ,
                    RetryBudget.initial(3)
            );

            assertThat(decision.isInFlight()).isTrue();
            assertThat(decision.executionTier()).isEqualTo(ExecutionTier.IN_FLIGHT);
        }

        @Test
        @DisplayName("Assigns DEFERRED when delay exceeds 2,000 ms (e.g. 2,001 ms)")
        void deferredWhenOverTwoSeconds() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.USER_RATE_LIMITED,
                    403,
                    "userRateLimitExceeded",
                    RetryEligibility.RETRYABLE,
                    Optional.of(Duration.ofMillis(2001)),
                    "Slightly over 2s delay"
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.PROVIDER_READ,
                    RetryBudget.initial(3)
            );

            assertThat(decision.isRetryPermitted()).isTrue();
            assertThat(decision.isInFlight()).isFalse();
            assertThat(decision.isDeferred()).isTrue();
            assertThat(decision.executionTier()).isEqualTo(ExecutionTier.DEFERRED);
        }

        @Test
        @DisplayName("Assigns DEFERRED for second retry attempt even if delay is under 2,000 ms")
        void deferredOnSecondAttemptEvenIfShortDelay() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.USER_RATE_LIMITED,
                    403,
                    "userRateLimitExceeded",
                    RetryEligibility.RETRYABLE,
                    Optional.of(Duration.ofMillis(500)), // short delay
                    "Short delay"
            );

            // Attempt 2 of 3 (this is the 2nd retry attempt)
            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.PROVIDER_READ,
                    RetryBudget.of(2, 3)
            );

            assertThat(decision.isRetryPermitted()).isTrue();
            assertThat(decision.isInFlight()).isFalse();
            assertThat(decision.isDeferred()).isTrue();
            assertThat(decision.executionTier()).isEqualTo(ExecutionTier.DEFERRED);
            assertThat(decision.reason()).contains("maximum 1 in-flight micro-retry permitted");
        }
    }

    @Nested
    @DisplayName("4. Operation-Specific Safety & Ambiguous Outcome Protection")
    class OperationSafetyTests {

        @Test
        @DisplayName("Rejects blind retry and flags RECONCILIATION_REQUIRED for mutating track insertion on 5xx server error")
        void requiresReconciliationForMutatingTrackInsertionOn5xx() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.TRANSIENT_SERVER_ERROR,
                    503,
                    "backendError",
                    RetryEligibility.RETRYABLE,
                    Optional.empty(),
                    "Service Unavailable"
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.TRACK_INSERTION,
                    RetryBudget.initial(3)
            );

            assertThat(decision.isRetryPermitted()).isFalse();
            assertThat(decision.isReconciliationRequired()).isTrue();
            assertThat(decision.decisionType()).isEqualTo(RetryDecisionType.RECONCILIATION_REQUIRED);
            assertThat(decision.isDeferred()).isTrue();
            assertThat(decision.executionTier()).isEqualTo(ExecutionTier.DEFERRED);
            assertThat(decision.reason()).contains("reconciliation required before retry to prevent duplicate mutations");
        }

        @Test
        @DisplayName("Rejects blind retry and flags RECONCILIATION_REQUIRED for playlist creation on network timeout")
        void requiresReconciliationForPlaylistCreationOnTimeout() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.TRANSIENT_SERVER_ERROR,
                    null,
                    "networkTimeout",
                    RetryEligibility.RETRYABLE,
                    Optional.empty(),
                    "I/O timeout during provider call"
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.DESTINATION_PLAYLIST_CREATION,
                    RetryBudget.initial(3)
            );

            assertThat(decision.isRetryPermitted()).isFalse();
            assertThat(decision.isReconciliationRequired()).isTrue();
            assertThat(decision.decisionType()).isEqualTo(RetryDecisionType.RECONCILIATION_REQUIRED);
            assertThat(decision.isDeferred()).isTrue();
        }

        @Test
        @DisplayName("Authorizes immediate retry for idempotent PROVIDER_READ on 5xx server error")
        void authorizesRetryForIdempotentProviderReadOn5xx() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.TRANSIENT_SERVER_ERROR,
                    503,
                    "backendError",
                    RetryEligibility.RETRYABLE,
                    Optional.empty(),
                    "Service Unavailable"
            );

            // PROVIDER_READ is idempotent; safe to retry with backoff
            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.PROVIDER_READ,
                    RetryBudget.initial(3)
            );

            assertThat(decision.isRetryPermitted()).isTrue();
            assertThat(decision.isReconciliationRequired()).isFalse();
            assertThat(decision.decisionType()).isEqualTo(RetryDecisionType.RETRY_PERMITTED);
        }

        @Test
        @DisplayName("Authorizes retry for mutating track insertion on HTTP 429 rate limit (not ambiguous)")
        void authorizesRetryForMutatingOperationOnRateLimit() {
            // Rate-limiting happens BEFORE mutation execution, so remote state is NOT ambiguous
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.RATE_LIMITED,
                    429,
                    "rateLimitExceeded",
                    RetryEligibility.RETRYABLE,
                    Optional.of(Duration.ofSeconds(5)),
                    "Rate limit"
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.TRACK_INSERTION,
                    RetryBudget.initial(3)
            );

            assertThat(decision.isRetryPermitted()).isTrue();
            assertThat(decision.isReconciliationRequired()).isFalse();
            assertThat(decision.decisionType()).isEqualTo(RetryDecisionType.RETRY_PERMITTED);
            assertThat(decision.delay()).isEqualTo(Duration.ofSeconds(5));
            assertThat(decision.isDeferred()).isTrue(); // 5s > 2s
        }

        @Test
        @DisplayName("Rejects blind retry and flags RECONCILIATION_REQUIRED for mutating track insertion with null diagnostic message")
        void requiresReconciliationForMutatingWriteWithMissingDiagnosticMessage() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.TRANSIENT_SERVER_ERROR,
                    null,
                    null,
                    RetryEligibility.RETRYABLE,
                    Optional.empty(),
                    null
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.TRACK_INSERTION,
                    RetryBudget.initial(3)
            );

            assertThat(decision.isRetryPermitted()).isFalse();
            assertThat(decision.isReconciliationRequired()).isTrue();
            assertThat(decision.decisionType()).isEqualTo(RetryDecisionType.RECONCILIATION_REQUIRED);
            assertThat(decision.isDeferred()).isTrue();
        }

        @Test
        @DisplayName("Rejects blind retry and flags RECONCILIATION_REQUIRED for mutating write with unfamiliar diagnostic message")
        void requiresReconciliationForMutatingWriteWithUnfamiliarDiagnosticMessage() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.TRANSIENT_SERVER_ERROR,
                    null,
                    "CORRUPT_FRAME",
                    RetryEligibility.RETRYABLE,
                    Optional.empty(),
                    "XYZ_UNEXPECTED_FRAME_DROP_ERR_99"
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.DESTINATION_PLAYLIST_CREATION,
                    RetryBudget.initial(3)
            );

            assertThat(decision.isRetryPermitted()).isFalse();
            assertThat(decision.isReconciliationRequired()).isTrue();
            assertThat(decision.decisionType()).isEqualTo(RetryDecisionType.RECONCILIATION_REQUIRED);
            assertThat(decision.isDeferred()).isTrue();
        }

        @Test
        @DisplayName("Rejects blind retry and flags RECONCILIATION_REQUIRED for mutating write on HTTP 500 with unfamiliar diagnostic message")
        void requiresReconciliationForMutatingWriteOn500WithUnfamiliarMessage() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.TRANSIENT_SERVER_ERROR,
                    500,
                    "unknownInternalError",
                    RetryEligibility.RETRYABLE,
                    Optional.empty(),
                    "Custom backend error message with zero keywords"
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.TRACK_INSERTION,
                    RetryBudget.initial(3)
            );

            assertThat(decision.isRetryPermitted()).isFalse();
            assertThat(decision.isReconciliationRequired()).isTrue();
            assertThat(decision.decisionType()).isEqualTo(RetryDecisionType.RECONCILIATION_REQUIRED);
            assertThat(decision.isDeferred()).isTrue();
        }

        @Test
        @DisplayName("Authorizes retry for idempotent PROVIDER_READ on unfamiliar transport error")
        void authorizesRetryForIdempotentProviderReadOnUnfamiliarTransportError() {
            ProviderFailureClassification classification = new ProviderFailureClassification(
                    ProviderFailureCategory.TRANSIENT_SERVER_ERROR,
                    null,
                    "CORRUPT_FRAME",
                    RetryEligibility.RETRYABLE,
                    Optional.empty(),
                    "XYZ_UNEXPECTED_FRAME_DROP_ERR_99"
            );

            RetryDecision decision = policy.evaluate(
                    classification,
                    RetryOperationType.PROVIDER_READ,
                    RetryBudget.initial(3)
            );

            assertThat(decision.isRetryPermitted()).isTrue();
            assertThat(decision.isReconciliationRequired()).isFalse();
            assertThat(decision.decisionType()).isEqualTo(RetryDecisionType.RETRY_PERMITTED);
        }
    }
}

