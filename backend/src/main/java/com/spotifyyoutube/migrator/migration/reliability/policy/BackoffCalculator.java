package com.spotifyyoutube.migrator.migration.reliability.policy;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Calculates exponential backoff with AWS Full Jitter (Phase 13C).
 * <p>
 * Implements the Full Jitter formula:
 * <pre>
 *   delay = random(0, min(cap, baseDelay * 2^attempt))
 * </pre>
 * Invariant rules:
 * <ul>
 *   <li>Guarantees zero lower bound (never negative).</li>
 *   <li>Guarantees delay does not exceed configured maximum cap.</li>
 *   <li>Completely safe against arithmetic overflow for large attempt counts or extreme durations.</li>
 *   <li>Safely handles sub-millisecond durations with nanosecond precision without division-by-zero errors.</li>
 *   <li>Accepts an injectable random source ({@link DoubleSupplier}) for deterministic unit testing.</li>
 *   <li>Avoids global shared mutable random state by defaulting to {@link ThreadLocalRandom}.</li>
 * </ul>
 */
@Component
public class BackoffCalculator {

    public static final Duration DEFAULT_BASE_DELAY = Duration.ofMillis(500);
    public static final Duration DEFAULT_MAX_DELAY = Duration.ofSeconds(30);

    private final Duration baseDelay;
    private final Duration maxDelay;
    private final DoubleSupplier randomSupplier;

    /**
     * Constructs a calculator with default base delay (500 ms) and max delay (30 s).
     */
    public BackoffCalculator() {
        this(DEFAULT_BASE_DELAY, DEFAULT_MAX_DELAY);
    }

    /**
     * Constructs a calculator with custom base and maximum delays, using {@link ThreadLocalRandom}.
     *
     * @param baseDelay base backoff duration
     * @param maxDelay maximum backoff cap
     */
    public BackoffCalculator(Duration baseDelay, Duration maxDelay) {
        this(baseDelay, maxDelay, () -> ThreadLocalRandom.current().nextDouble());
    }

    /**
     * Constructs a calculator with custom delays and injectable random source for deterministic testing.
     *
     * @param baseDelay base backoff duration
     * @param maxDelay maximum backoff cap
     * @param randomSupplier supplier yielding double values in [0.0, 1.0]
     */
    public BackoffCalculator(Duration baseDelay, Duration maxDelay, DoubleSupplier randomSupplier) {
        this.baseDelay = Objects.requireNonNull(baseDelay, "baseDelay must not be null");
        this.maxDelay = Objects.requireNonNull(maxDelay, "maxDelay must not be null");
        this.randomSupplier = Objects.requireNonNull(randomSupplier, "randomSupplier must not be null");

        if (baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("baseDelay must be positive, but got: " + baseDelay);
        }
        if (maxDelay.isNegative() || maxDelay.isZero()) {
            throw new IllegalArgumentException("maxDelay must be positive, but got: " + maxDelay);
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay (" + maxDelay + ") must not be less than baseDelay (" + baseDelay + ")");
        }
    }

    /**
     * Calculates the Full Jitter exponential backoff duration for the specified attempt.
     * Safely handles sub-millisecond precision and prevents overflow on extremely large durations.
     *
     * @param attempt non-negative attempt index (0-based or 1-based)
     * @return non-negative jittered duration bounded by [0, min(cap, baseDelay * 2^attempt)]
     */
    public Duration calculateBackoff(int attempt) {
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must be non-negative, but got: " + attempt);
        }

        Duration ceiling = calculateCeiling(attempt);
        double r = randomSupplier.getAsDouble();
        if (r < 0.0 || r > 1.0) {
            throw new IllegalStateException("randomSupplier returned value out of range [0.0, 1.0]: " + r);
        }

        if (r <= 0.0) {
            return Duration.ZERO;
        }
        if (r >= 1.0) {
            return ceiling;
        }

        return scaleDuration(ceiling, r);
    }

    /**
     * Computes the unjittered maximum ceiling for the specified attempt: {@code min(cap, baseDelay * 2^attempt)}.
     * Fully safe against overflow, sub-millisecond durations, and large attempt values.
     *
     * @param attempt non-negative attempt index
     * @return ceiling duration
     */
    public Duration calculateCeiling(int attempt) {
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must be non-negative, but got: " + attempt);
        }

        if (attempt >= 62) {
            return maxDelay;
        }

        long multiplier = 1L << attempt;
        try {
            Duration multiplied = baseDelay.multipliedBy(multiplier);
            return multiplied.compareTo(maxDelay) > 0 ? maxDelay : multiplied;
        } catch (ArithmeticException e) {
            return maxDelay;
        }
    }

    /**
     * Computes the unjittered maximum ceiling for the specified attempt in milliseconds.
     * Safely clamps to {@link Long#MAX_VALUE} if the ceiling exceeds millisecond capacity.
     *
     * @param attempt non-negative attempt index
     * @return ceiling in milliseconds
     */
    public long calculateCeilingMillis(int attempt) {
        Duration ceiling = calculateCeiling(attempt);
        try {
            return ceiling.toMillis();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private Duration scaleDuration(Duration duration, double factor) {
        try {
            long nanos = duration.toNanos();
            long scaledNanos = Math.round(factor * nanos);
            return Duration.ofNanos(Math.max(0L, scaledNanos));
        } catch (ArithmeticException e) {
            // Duration exceeds ~292 years (nanoseconds overflow). Scale milliseconds:
            try {
                long millis = duration.toMillis();
                long scaledMillis = Math.round(factor * millis);
                return Duration.ofMillis(Math.max(0L, scaledMillis));
            } catch (ArithmeticException e2) {
                // Duration exceeds ~292 million years (milliseconds overflow). Scale seconds:
                long seconds = duration.toSeconds();
                long scaledSeconds = Math.round(factor * seconds);
                return Duration.ofSeconds(Math.max(0L, scaledSeconds));
            }
        }
    }

    public Duration getBaseDelay() {
        return baseDelay;
    }

    public Duration getMaxDelay() {
        return maxDelay;
    }
}

