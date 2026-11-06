package com.spotifyyoutube.migrator.migration.reliability.classification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 13B: RetryAfterParser Unit Tests")
class RetryAfterParserTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-06T10:00:00Z");
    private Clock fixedClock;
    private RetryAfterParser parser;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        parser = new RetryAfterParser(fixedClock);
    }

    @Nested
    @DisplayName("1. Delta-Seconds Parsing")
    class DeltaSecondsTests {

        @Test
        @DisplayName("Parses valid non-negative integer delta-seconds")
        void parsesValidDeltaSeconds() {
            RetryAfterResult result = parser.parse("120");

            assertThat(result.isValid()).isTrue();
            assertThat(result.status()).isEqualTo(RetryAfterResult.RetryAfterStatus.VALID);
            assertThat(result.duration()).isEqualTo(Duration.ofSeconds(120));
            assertThat(result.delay()).contains(Duration.ofSeconds(120));
            assertThat(result.rawHeader()).isEqualTo("120");
        }

        @Test
        @DisplayName("Parses zero seconds as zero duration")
        void parsesZeroSeconds() {
            RetryAfterResult result = parser.parse("0");

            assertThat(result.isValid()).isTrue();
            assertThat(result.duration()).isEqualTo(Duration.ZERO);
            assertThat(result.delay()).contains(Duration.ZERO);
        }

        @Test
        @DisplayName("Trims leading and trailing whitespace around delta-seconds")
        void trimsWhitespaceAroundDeltaSeconds() {
            RetryAfterResult result = parser.parse("   60   ");

            assertThat(result.isValid()).isTrue();
            assertThat(result.duration()).isEqualTo(Duration.ofSeconds(60));
        }

        @Test
        @DisplayName("Parses standard delta-seconds value (e.g. 45 seconds)")
        void parsesStandardDeltaSeconds() {
            RetryAfterResult result = parser.parse("45");

            assertThat(result.isValid()).isTrue();
            assertThat(result.duration()).isEqualTo(Duration.ofSeconds(45));
            assertThat(result.delay()).contains(Duration.ofSeconds(45));
        }

        @Test
        @DisplayName("Rejects explicit positive signed integer delta-seconds as invalid per RFC 9110")
        void rejectsExplicitPositiveSignDeltaSeconds() {
            RetryAfterResult result = parser.parse("+45");

            assertThat(result.isInvalid()).isTrue();
            assertThat(result.status()).isEqualTo(RetryAfterResult.RetryAfterStatus.INVALID);
            assertThat(result.delay()).isEmpty();
            assertThat(result.failureReason()).contains("Explicit positive sign is not allowed in delta-seconds");

            // Verify other explicit positive sign variants are also rejected
            RetryAfterResult resultZero = parser.parse("+0");
            assertThat(resultZero.isInvalid()).isTrue();
            assertThat(resultZero.delay()).isEmpty();

            RetryAfterResult result120 = parser.parse("+120");
            assertThat(result120.isInvalid()).isTrue();
            assertThat(result120.delay()).isEmpty();
        }

        @Test
        @DisplayName("Rejects negative delta-seconds as invalid")
        void rejectsNegativeDeltaSeconds() {
            RetryAfterResult result = parser.parse("-10");

            assertThat(result.isInvalid()).isTrue();
            assertThat(result.status()).isEqualTo(RetryAfterResult.RetryAfterStatus.INVALID);
            assertThat(result.delay()).isEmpty();
            assertThat(result.failureReason()).contains("Negative delta-seconds is not allowed");
        }

        @Test
        @DisplayName("Rejects decimal numbers as invalid")
        void rejectsDecimalNumbers() {
            RetryAfterResult result = parser.parse("12.5");

            assertThat(result.isInvalid()).isTrue();
            assertThat(result.status()).isEqualTo(RetryAfterResult.RetryAfterStatus.INVALID);
            assertThat(result.delay()).isEmpty();
            assertThat(result.failureReason()).contains("must be an integer, but got decimal");
        }

        @Test
        @DisplayName("Handles extremely large numeric values exceeding Long.MAX_VALUE safely")
        void handlesNumericOverflowSafely() {
            RetryAfterResult result = parser.parse("999999999999999999999999999");

            assertThat(result.isInvalid()).isTrue();
            assertThat(result.status()).isEqualTo(RetryAfterResult.RetryAfterStatus.INVALID);
            assertThat(result.delay()).isEmpty();
            assertThat(result.failureReason()).contains("Numeric overflow");
        }

        @Test
        @DisplayName("Parses large valid seconds without truncation")
        void parsesLargeValidSecondsWithoutTruncation() {
            // 86400 seconds = 24 hours
            RetryAfterResult result = parser.parse("86400");

            assertThat(result.isValid()).isTrue();
            assertThat(result.duration()).isEqualTo(Duration.ofHours(24));
        }
    }

    @Nested
    @DisplayName("2. HTTP-Date Parsing")
    class HttpDateTests {

        @Test
        @DisplayName("Parses valid HTTP-date in the future into exact duration from Clock")
        void parsesFutureHttpDate() {
            // FIXED_NOW = 2026-11-06T10:00:00Z; target = 2026-11-06T12:00:00Z (2 hours = 7200 seconds later)
            String futureDate = "Fri, 06 Nov 2026 12:00:00 GMT";
            RetryAfterResult result = parser.parse(futureDate);

            assertThat(result.isValid()).isTrue();
            assertThat(result.status()).isEqualTo(RetryAfterResult.RetryAfterStatus.VALID);
            assertThat(result.duration()).isEqualTo(Duration.ofHours(2));
            assertThat(result.duration().toSeconds()).isEqualTo(7200L);
            assertThat(result.delay()).contains(Duration.ofHours(2));
        }

        @Test
        @DisplayName("Returns Duration.ZERO when HTTP-date is in the past")
        void returnsZeroForPastHttpDate() {
            // FIXED_NOW = 2026-11-06T10:00:00Z; target = 2026-11-06T08:00:00Z (2 hours earlier)
            String pastDate = "Fri, 06 Nov 2026 08:00:00 GMT";
            RetryAfterResult result = parser.parse(pastDate);

            assertThat(result.isValid()).isTrue();
            assertThat(result.duration()).isEqualTo(Duration.ZERO);
            assertThat(result.delay()).contains(Duration.ZERO);
        }

        @Test
        @DisplayName("Returns Duration.ZERO when HTTP-date exactly equals current instant")
        void returnsZeroForExactCurrentInstant() {
            String exactDate = "Fri, 06 Nov 2026 10:00:00 GMT";
            RetryAfterResult result = parser.parse(exactDate);

            assertThat(result.isValid()).isTrue();
            assertThat(result.duration()).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("Parses RFC-850 legacy format successfully")
        void parsesRfc850DateFormat() {
            String rfc850Date = "Friday, 06-Nov-26 11:00:00 GMT";
            RetryAfterResult result = parser.parse(rfc850Date);

            assertThat(result.isValid()).isTrue();
            assertThat(result.duration()).isEqualTo(Duration.ofHours(1));
        }

        @Test
        @DisplayName("Rejects malformed date string as invalid")
        void rejectsMalformedDate() {
            String malformed = "Fri, 99 Nov 2026 99:99:99 GMT";
            RetryAfterResult result = parser.parse(malformed);

            assertThat(result.isInvalid()).isTrue();
            assertThat(result.status()).isEqualTo(RetryAfterResult.RetryAfterStatus.INVALID);
            assertThat(result.delay()).isEmpty();
            assertThat(result.failureReason()).contains("Unparseable Retry-After format");
        }
    }

    @Nested
    @DisplayName("3. Missing, Empty & Malformed Headers")
    class MissingAndMalformedTests {

        @Test
        @DisplayName("Returns MISSING when header is null")
        void returnsMissingForNullHeader() {
            RetryAfterResult result = parser.parse((String) null);

            assertThat(result.isMissing()).isTrue();
            assertThat(result.status()).isEqualTo(RetryAfterResult.RetryAfterStatus.MISSING);
            assertThat(result.delay()).isEmpty();
            assertThat(result.duration()).isNull();
        }

        @Test
        @DisplayName("Returns INVALID when header is empty string")
        void returnsInvalidForEmptyString() {
            RetryAfterResult result = parser.parse("");

            assertThat(result.isInvalid()).isTrue();
            assertThat(result.status()).isEqualTo(RetryAfterResult.RetryAfterStatus.INVALID);
            assertThat(result.delay()).isEmpty();
            assertThat(result.failureReason()).contains("empty or blank");
        }

        @Test
        @DisplayName("Returns INVALID when header is whitespace only")
        void returnsInvalidForWhitespaceOnly() {
            RetryAfterResult result = parser.parse("    ");

            assertThat(result.isInvalid()).isTrue();
            assertThat(result.failureReason()).contains("empty or blank");
        }

        @Test
        @DisplayName("Returns INVALID when header is arbitrary non-numeric text")
        void returnsInvalidForArbitraryText() {
            RetryAfterResult result = parser.parse("tomorrow-morning");

            assertThat(result.isInvalid()).isTrue();
            assertThat(result.status()).isEqualTo(RetryAfterResult.RetryAfterStatus.INVALID);
            assertThat(result.failureReason()).contains("Unparseable Retry-After format");
        }
    }

    @Nested
    @DisplayName("4. HttpHeaders Overloads & Convenience Methods")
    class HttpHeadersOverloadTests {

        @Test
        @DisplayName("Parses Retry-After from Spring HttpHeaders")
        void parsesFromHttpHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, "30");

            RetryAfterResult result = parser.parse(headers);

            assertThat(result.isValid()).isTrue();
            assertThat(result.duration()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("Returns MISSING when HttpHeaders lacks Retry-After header")
        void returnsMissingWhenHeaderAbsentInHttpHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");

            RetryAfterResult result = parser.parse(headers);

            assertThat(result.isMissing()).isTrue();
        }

        @Test
        @DisplayName("Returns MISSING when HttpHeaders is null")
        void returnsMissingWhenHttpHeadersIsNull() {
            RetryAfterResult result = parser.parse((HttpHeaders) null);

            assertThat(result.isMissing()).isTrue();
        }

        @Test
        @DisplayName("Convenience parseDelay returns Optional duration")
        void parseDelayReturnsOptional() {
            assertThat(parser.parseDelay("15")).contains(Duration.ofSeconds(15));
            assertThat(parser.parseDelay("invalid")).isEmpty();
            assertThat(parser.parseDelay((String) null)).isEmpty();
        }
    }
}
