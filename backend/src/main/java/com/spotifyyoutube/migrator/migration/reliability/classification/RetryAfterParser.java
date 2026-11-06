package com.spotifyyoutube.migrator.migration.reliability.classification;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses HTTP Retry-After headers in compliance with RFC 9110 Section 10.2.3 (Phase 13B).
 * <p>
 * Supports both standard formats:
 * <ul>
 *   <li>Delta-seconds: Non-negative integer seconds (e.g., "120")</li>
 *   <li>HTTP-date: RFC-compliant timestamp (e.g., "Fri, 06 Nov 2026 12:00:00 GMT")</li>
 * </ul>
 * <p>
 * Invariant rules:
 * <ul>
 *   <li>Never silently truncates or shortens a valid provider-specified delay.</li>
 *   <li>Returns {@link Duration#ZERO} when the parsed HTTP-date is already in the past.</li>
 *   <li>Safely handles negative numbers, decimals, malformed strings, and integer overflow.</li>
 *   <li>Uses an injectable {@link Clock} for deterministic unit testing.</li>
 * </ul>
 */
@Component
public class RetryAfterParser {

    private static final Pattern DIGITS_ONLY = Pattern.compile("^[0-9]+$");
    private static final Pattern DECIMAL_NUMBER = Pattern.compile("^[+-]?[0-9]+\\.[0-9]+$");

    // Standard RFC formatters for HTTP-date
    private static final DateTimeFormatter RFC_1123_FORMATTER = DateTimeFormatter.RFC_1123_DATE_TIME;
    private static final DateTimeFormatter RFC_850_FORMATTER = DateTimeFormatter.ofPattern(
            "EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US
    );
    private static final DateTimeFormatter ASCTIME_FORMATTER = DateTimeFormatter.ofPattern(
            "EEE MMM d HH:mm:ss yyyy", Locale.US
    ).withZone(ZoneOffset.UTC);

    private final Clock clock;

    /**
     * Constructs a parser with system UTC clock.
     */
    public RetryAfterParser() {
        this(Clock.systemUTC());
    }

    /**
     * Constructs a parser with an injectable Clock for deterministic testing.
     *
     * @param clock clock instance
     */
    public RetryAfterParser(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Parses the Retry-After header from Spring HttpHeaders.
     *
     * @param headers HTTP headers
     * @return structured RetryAfterResult
     */
    public RetryAfterResult parse(HttpHeaders headers) {
        if (headers == null) {
            return RetryAfterResult.missing();
        }
        String headerValue = headers.getFirst(HttpHeaders.RETRY_AFTER);
        return parse(headerValue);
    }

    /**
     * Parses a raw Retry-After header string.
     *
     * @param headerValue raw header value
     * @return structured RetryAfterResult
     */
    public RetryAfterResult parse(String headerValue) {
        if (headerValue == null) {
            return RetryAfterResult.missing();
        }

        String trimmed = headerValue.trim();
        if (trimmed.isEmpty()) {
            return RetryAfterResult.invalid(headerValue, "Retry-After header is empty or blank");
        }

        // 1. Check for signed numbers (+ or -)
        if (trimmed.startsWith("-")) {
            return RetryAfterResult.invalid(headerValue, "Negative delta-seconds is not allowed: " + trimmed);
        }
        if (trimmed.startsWith("+")) {
            return RetryAfterResult.invalid(headerValue, "Explicit positive sign is not allowed in delta-seconds: " + trimmed);
        }

        // 2. Check for decimal numbers
        if (DECIMAL_NUMBER.matcher(trimmed).matches()) {
            return RetryAfterResult.invalid(headerValue, "Delta-seconds must be an integer, but got decimal: " + trimmed);
        }

        // 3. Attempt parsing as delta-seconds (non-negative decimal digits only per RFC 9110)
        if (DIGITS_ONLY.matcher(trimmed).matches()) {
            try {
                long seconds = Long.parseLong(trimmed);
                return RetryAfterResult.valid(Duration.ofSeconds(seconds), headerValue);
            } catch (NumberFormatException e) {
                // Number exceeds Long.MAX_VALUE bounds
                return RetryAfterResult.invalid(headerValue, "Numeric overflow: delta-seconds exceeds representable bounds: " + trimmed);
            } catch (ArithmeticException e) {
                return RetryAfterResult.invalid(headerValue, "Numeric overflow: delta-seconds duration overflow: " + trimmed);
            }
        }

        // 4. Attempt parsing as HTTP-date
        Optional<Instant> parsedInstant = parseHttpDate(trimmed);
        if (parsedInstant.isPresent()) {
            Instant target = parsedInstant.get();
            Instant now = clock.instant();

            if (target.isBefore(now)) {
                // Provider date is in the past -> zero duration per RFC 9110
                return RetryAfterResult.valid(Duration.ZERO, headerValue);
            }
            return RetryAfterResult.valid(Duration.between(now, target), headerValue);
        }

        // 5. Unrecognized format
        return RetryAfterResult.invalid(headerValue, "Unparseable Retry-After format: " + trimmed);
    }

    /**
     * Convenience method returning an Optional Duration if the header is valid.
     *
     * @param headerValue raw header value
     * @return Optional containing parsed duration if valid, empty otherwise
     */
    public Optional<Duration> parseDelay(String headerValue) {
        return parse(headerValue).delay();
    }

    /**
     * Convenience method returning an Optional Duration from HttpHeaders.
     *
     * @param headers HTTP headers
     * @return Optional containing parsed duration if valid, empty otherwise
     */
    public Optional<Duration> parseDelay(HttpHeaders headers) {
        return parse(headers).delay();
    }

    private Optional<Instant> parseHttpDate(String dateStr) {
        // Attempt RFC-1123 (standard IMF-fixdate)
        try {
            return Optional.of(RFC_1123_FORMATTER.parse(dateStr, Instant::from));
        } catch (DateTimeParseException ignored) {
            // Fall through to secondary formats
        }

        // Attempt RFC-850 format
        try {
            return Optional.of(RFC_850_FORMATTER.parse(dateStr, Instant::from));
        } catch (DateTimeParseException ignored) {
            // Fall through to tertiary format
        }

        // Attempt ANSI C asctime format
        try {
            return Optional.of(ASCTIME_FORMATTER.parse(dateStr, Instant::from));
        } catch (DateTimeParseException ignored) {
            // Unparseable date
        }

        return Optional.empty();
    }
}
