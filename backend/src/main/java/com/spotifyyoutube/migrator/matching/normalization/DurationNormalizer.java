package com.spotifyyoutube.migrator.matching.normalization;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.format.DateTimeParseException;

/**
 * Normalizes duration metadata from heterogeneous providers into a canonical,
 * precise {@link Duration} representation for subsequent track matching.
 * <p>
 * Supports:
 * <ul>
 *   <li>Spotify-style millisecond values (e.g. {@code 200000L})</li>
 *   <li>YouTube-style ISO-8601 duration strings (e.g. {@code "PT3M20S"})</li>
 * </ul>
 * <p>
 * Error and null contract:
 * <ul>
 *   <li>{@code null} or blank input yields {@code null} (absent duration).</li>
 *   <li>Legitimate zero durations (e.g. {@code 0L}, {@code "PT0S"}) yield {@link Duration#ZERO}.</li>
 *   <li>Negative durations reject with {@link IllegalArgumentException}.</li>
 *   <li>Malformed ISO-8601 duration strings reject with {@link IllegalArgumentException}.</li>
 *   <li>Arithmetic overflow or extreme out-of-range values reject with {@link IllegalArgumentException}.</li>
 * </ul>
 * <p>
 * This component is stateless, deterministic, pure, and free of external dependencies.
 */
@Component
public class DurationNormalizer {

    /**
     * Converts a duration expressed in milliseconds into a {@link Duration}.
     * Preserves exact millisecond precision without rounding or floating-point conversions.
     *
     * @param milliseconds duration in milliseconds, may be null
     * @return normalized {@link Duration}, or {@code null} if input is null
     * @throws IllegalArgumentException if milliseconds is negative
     */
    public Duration fromMilliseconds(Long milliseconds) {
        if (milliseconds == null) {
            return null;
        }
        if (milliseconds < 0) {
            throw new IllegalArgumentException("Duration milliseconds cannot be negative: " + milliseconds);
        }
        if (milliseconds == 0) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(milliseconds);
    }

    /**
     * Overloaded convenience method converting a duration expressed in integer milliseconds
     * into a {@link Duration}.
     *
     * @param milliseconds duration in milliseconds, may be null
     * @return normalized {@link Duration}, or {@code null} if input is null
     * @throws IllegalArgumentException if milliseconds is negative
     */
    public Duration fromMilliseconds(Integer milliseconds) {
        return fromMilliseconds(milliseconds != null ? milliseconds.longValue() : null);
    }

    /**
     * Converts a YouTube-style ISO-8601 duration string into a {@link Duration}.
     * Supports standard ISO-8601 duration formats (e.g. {@code "PT3M20S"}, {@code "PT45S"},
     * {@code "PT1H2M3S"}, {@code "PT0S"}) and preserves supported fractional second precision.
     *
     * @param value the raw ISO-8601 duration string, may be null or blank
     * @return normalized {@link Duration}, or {@code null} if input is null or blank
     * @throws IllegalArgumentException if the string is malformed, represents a negative duration,
     *                                  or exceeds supported arithmetic range
     */
    public Duration fromIso8601(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();

        try {
            Duration parsed = Duration.parse(trimmed);
            if (parsed.isNegative()) {
                throw new IllegalArgumentException("ISO-8601 duration cannot be negative: " + value);
            }
            return parsed;
        } catch (DateTimeParseException e) {
            if (e.getCause() instanceof NumberFormatException || e.getCause() instanceof ArithmeticException) {
                throw new IllegalArgumentException("ISO-8601 duration exceeds supported range: " + value, e);
            }
            throw new IllegalArgumentException("Invalid ISO-8601 duration format: " + value, e);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("ISO-8601 duration exceeds supported range: " + value, e);
        }
    }
}
