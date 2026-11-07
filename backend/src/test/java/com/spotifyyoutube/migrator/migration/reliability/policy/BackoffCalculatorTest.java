package com.spotifyyoutube.migrator.migration.reliability.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Phase 13C: BackoffCalculator Unit Tests")
class BackoffCalculatorTest {

    @Test
    @DisplayName("Calculates first retry delay range within [0, baseDelay * 2^attempt]")
    void firstRetryDelayRange() {
        Duration base = Duration.ofMillis(500);
        Duration max = Duration.ofSeconds(30);

        BackoffCalculator minCalc = new BackoffCalculator(base, max, () -> 0.0);
        BackoffCalculator midCalc = new BackoffCalculator(base, max, () -> 0.5);
        BackoffCalculator maxCalc = new BackoffCalculator(base, max, () -> 1.0);

        // For attempt 1, ceiling = min(30s, 500ms * 2^1) = 1,000 ms
        assertThat(minCalc.calculateBackoff(1)).isEqualTo(Duration.ZERO);
        assertThat(midCalc.calculateBackoff(1)).isEqualTo(Duration.ofMillis(500));
        assertThat(maxCalc.calculateBackoff(1)).isEqualTo(Duration.ofMillis(1000));
    }

    @Test
    @DisplayName("Exhibits exponential ceiling growth for successive attempts")
    void exponentialCeilingGrowth() {
        BackoffCalculator calc = new BackoffCalculator(Duration.ofMillis(500), Duration.ofSeconds(30));

        assertThat(calc.calculateCeilingMillis(0)).isEqualTo(500L);
        assertThat(calc.calculateCeilingMillis(1)).isEqualTo(1000L);
        assertThat(calc.calculateCeilingMillis(2)).isEqualTo(2000L);
        assertThat(calc.calculateCeilingMillis(3)).isEqualTo(4000L);
        assertThat(calc.calculateCeilingMillis(4)).isEqualTo(8000L);
        assertThat(calc.calculateCeilingMillis(5)).isEqualTo(16000L);
        assertThat(calc.calculateCeilingMillis(6)).isEqualTo(30000L); // capped
    }

    @Test
    @DisplayName("Strictly bounds delay by configured maximum cap")
    void respectsMaximumCap() {
        Duration base = Duration.ofSeconds(1);
        Duration max = Duration.ofSeconds(10);
        BackoffCalculator calc = new BackoffCalculator(base, max, () -> 1.0);

        // attempt 3: 1s * 2^3 = 8s <= 10s
        assertThat(calc.calculateBackoff(3)).isEqualTo(Duration.ofSeconds(8));

        // attempt 4: 1s * 2^4 = 16s -> capped at 10s
        assertThat(calc.calculateBackoff(4)).isEqualTo(Duration.ofSeconds(10));

        // attempt 10: capped at 10s
        assertThat(calc.calculateBackoff(10)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("Guarantees non-negative zero lower bound for zero random factor")
    void zeroLowerBound() {
        BackoffCalculator calc = new BackoffCalculator(Duration.ofMillis(250), Duration.ofSeconds(5), () -> 0.0);

        for (int attempt = 0; attempt <= 10; attempt++) {
            Duration delay = calc.calculateBackoff(attempt);
            assertThat(delay).isEqualTo(Duration.ZERO);
            assertThat(delay.isNegative()).isFalse();
        }
    }

    @Test
    @DisplayName("Calculates safely without numeric overflow for arbitrarily large attempt counts")
    void overflowSafety() {
        Duration base = Duration.ofSeconds(1);
        Duration max = Duration.ofSeconds(60);
        BackoffCalculator calc = new BackoffCalculator(base, max, () -> 1.0);

        int[] largeAttempts = {30, 31, 32, 60, 62, 63, 64, 100, 1000, 1000000};
        for (int attempt : largeAttempts) {
            assertThat(calc.calculateCeilingMillis(attempt)).isEqualTo(60000L);
            assertThat(calc.calculateBackoff(attempt)).isEqualTo(Duration.ofSeconds(60));
        }
    }

    @Test
    @DisplayName("Produces deterministic results with controlled random supplier")
    void deterministicBehaviorWithControlledRandom() {
        Duration base = Duration.ofMillis(1000);
        Duration max = Duration.ofSeconds(20);
        // attempt 2: ceiling = min(20s, 1s * 4) = 4,000 ms. 0.75 * 4,000 = 3,000 ms
        BackoffCalculator calc = new BackoffCalculator(base, max, () -> 0.75);

        assertThat(calc.calculateBackoff(2)).isEqualTo(Duration.ofMillis(3000));
    }

    @Test
    @DisplayName("Rejects negative attempt counts")
    void rejectsNegativeAttempt() {
        BackoffCalculator calc = new BackoffCalculator();

        assertThatThrownBy(() -> calc.calculateBackoff(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempt must be non-negative");

        assertThatThrownBy(() -> calc.calculateCeilingMillis(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempt must be non-negative");
    }

    @Test
    @DisplayName("Rejects invalid configurations: nulls, non-positive delays, and max < base")
    void rejectsInvalidConfiguration() {
        Duration validBase = Duration.ofMillis(500);
        Duration validMax = Duration.ofSeconds(30);

        assertThatThrownBy(() -> new BackoffCalculator(null, validMax))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new BackoffCalculator(validBase, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new BackoffCalculator(validBase, validMax, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new BackoffCalculator(Duration.ZERO, validMax))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseDelay must be positive");

        assertThatThrownBy(() -> new BackoffCalculator(Duration.ofMillis(-100), validMax))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseDelay must be positive");

        assertThatThrownBy(() -> new BackoffCalculator(validBase, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDelay must be positive");

        assertThatThrownBy(() -> new BackoffCalculator(Duration.ofSeconds(10), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be less than baseDelay");
    }

    @Test
    @DisplayName("Rejects random supplier yielding values outside [0.0, 1.0]")
    void rejectsRandomSupplierOutOfRange() {
        BackoffCalculator tooHigh = new BackoffCalculator(Duration.ofMillis(500), Duration.ofSeconds(30), () -> 1.05);
        BackoffCalculator tooLow = new BackoffCalculator(Duration.ofMillis(500), Duration.ofSeconds(30), () -> -0.01);

        assertThatThrownBy(() -> tooHigh.calculateBackoff(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out of range [0.0, 1.0]");

        assertThatThrownBy(() -> tooLow.calculateBackoff(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out of range [0.0, 1.0]");
    }

    @Test
    @DisplayName("Handles sub-millisecond base delay safely with nanosecond precision without division by zero")
    void subMillisecondBaseDelayConfiguration() {
        Duration base = Duration.ofNanos(500); // 0.5 microseconds
        Duration max = Duration.ofMillis(10);

        BackoffCalculator minCalc = new BackoffCalculator(base, max, () -> 0.0);
        BackoffCalculator midCalc = new BackoffCalculator(base, max, () -> 0.5);
        BackoffCalculator maxCalc = new BackoffCalculator(base, max, () -> 1.0);

        // Attempt 1 ceiling = min(10ms, 500ns * 2) = 1,000 ns
        assertThat(midCalc.calculateCeiling(1)).isEqualTo(Duration.ofNanos(1000));
        assertThat(minCalc.calculateBackoff(1)).isEqualTo(Duration.ZERO);
        assertThat(midCalc.calculateBackoff(1)).isEqualTo(Duration.ofNanos(500));
        assertThat(maxCalc.calculateBackoff(1)).isEqualTo(Duration.ofNanos(1000));

        // Sub-millisecond toMillis safely returns 0 without arithmetic error
        assertThat(midCalc.calculateCeilingMillis(1)).isEqualTo(0L);
    }

    @Test
    @DisplayName("Handles extremely large durations up to Long.MAX_VALUE without arithmetic overflow")
    void extremelyLargeDurationsWithoutOverflow() {
        Duration base = Duration.ofSeconds(10);
        Duration max = Duration.ofSeconds(Long.MAX_VALUE);

        BackoffCalculator calc = new BackoffCalculator(base, max, () -> 1.0);

        // Attempt 1: 10s * 2^1 = 20s
        assertThat(calc.calculateCeiling(1)).isEqualTo(Duration.ofSeconds(20));
        assertThat(calc.calculateBackoff(1)).isEqualTo(Duration.ofSeconds(20));

        // Attempt 63: clamps safely to maxDelay
        assertThat(calc.calculateCeiling(63)).isEqualTo(max);
        assertThat(calc.calculateBackoff(63)).isEqualTo(max);

        // calculateCeilingMillis safely clamps to Long.MAX_VALUE
        assertThat(calc.calculateCeilingMillis(63)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("Scales durations exceeding nanosecond capacity (> 292 years) safely using millisecond fallback")
    void extremelyLargeBaseDelayScaling() {
        Duration base = Duration.ofDays(365 * 1000); // 1,000 years, exceeds 292 years nanosecond capacity
        Duration max = Duration.ofDays(365 * 2000);

        BackoffCalculator calc = new BackoffCalculator(base, max, () -> 0.5);

        // Ceiling for attempt 0 is 1000 years
        assertThat(calc.calculateCeiling(0)).isEqualTo(base);

        // Backoff with factor 0.5 safely yields 500 years via millisecond scaling fallback
        Duration backoff = calc.calculateBackoff(0);
        assertThat(backoff.toDays()).isEqualTo(365 * 500);
    }
}

