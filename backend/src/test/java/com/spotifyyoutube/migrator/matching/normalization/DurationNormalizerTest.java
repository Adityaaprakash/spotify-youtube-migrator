package com.spotifyyoutube.migrator.matching.normalization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DurationNormalizer Unit Tests")
class DurationNormalizerTest {

    private DurationNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new DurationNormalizer();
    }

    @Nested
    @DisplayName("Millisecond Conversion Tests")
    class MillisecondConversionTests {

        @Test
        @DisplayName("Should convert positive whole-second millisecond duration exactly")
        void shouldConvertPositiveWholeSecondMilliseconds() {
            Duration result = normalizer.fromMilliseconds(200_000L);

            assertThat(result).isEqualTo(Duration.ofSeconds(200));
            assertThat(result.toMillis()).isEqualTo(200_000L);
            assertThat(result.getSeconds()).isEqualTo(200L);
            assertThat(result.getNano()).isZero();
        }

        @Test
        @DisplayName("Should convert milliseconds containing fractional seconds preserving exact precision")
        void shouldConvertFractionalSecondMilliseconds() {
            Duration result = normalizer.fromMilliseconds(200_500L);

            assertThat(result).isEqualTo(Duration.ofSeconds(200, 500_000_000));
            assertThat(result.toMillis()).isEqualTo(200_500L);
            assertThat(result.getSeconds()).isEqualTo(200L);
            assertThat(result.getNano()).isEqualTo(500_000_000);
        }

        @Test
        @DisplayName("Should convert single millisecond preserving precision")
        void shouldConvertSingleMillisecond() {
            Duration result = normalizer.fromMilliseconds(1L);

            assertThat(result).isEqualTo(Duration.ofMillis(1L));
            assertThat(result.toMillis()).isEqualTo(1L);
            assertThat(result.getSeconds()).isZero();
            assertThat(result.getNano()).isEqualTo(1_000_000);
        }

        @Test
        @DisplayName("Should convert zero milliseconds to Duration.ZERO")
        void shouldConvertZeroMilliseconds() {
            Duration result = normalizer.fromMilliseconds(0L);

            assertThat(result).isEqualTo(Duration.ZERO);
            assertThat(result.isZero()).isTrue();
        }

        @Test
        @DisplayName("Should return null when millisecond input is null")
        void shouldReturnNullForNullMilliseconds() {
            assertThat(normalizer.fromMilliseconds((Long) null)).isNull();
            assertThat(normalizer.fromMilliseconds((Integer) null)).isNull();
        }

        @Test
        @DisplayName("Should support Integer millisecond overload")
        void shouldSupportIntegerOverload() {
            Duration fromInt = normalizer.fromMilliseconds(Integer.valueOf(200_000));
            Duration fromLong = normalizer.fromMilliseconds(200_000L);

            assertThat(fromInt).isEqualTo(fromLong);
            assertThat(fromInt).isEqualTo(Duration.ofSeconds(200));
        }

        @Test
        @DisplayName("Should convert large valid millisecond duration without error")
        void shouldConvertLargeValidMilliseconds() {
            // 100 hours in milliseconds: 100 * 3600 * 1000 = 360,000,000 ms
            Duration result = normalizer.fromMilliseconds(360_000_000L);

            assertThat(result).isEqualTo(Duration.ofHours(100));
            assertThat(result.toMillis()).isEqualTo(360_000_000L);
        }

        @Test
        @DisplayName("Should convert Long.MAX_VALUE without silent overflow")
        void shouldConvertLongMaxValueWithoutOverflow() {
            Duration result = normalizer.fromMilliseconds(Long.MAX_VALUE);

            assertThat(result).isNotNull();
            assertThat(result.getSeconds()).isEqualTo(Long.MAX_VALUE / 1000L);
            assertThat(result.getNano()).isEqualTo((int) ((Long.MAX_VALUE % 1000L) * 1_000_000L));
        }

        @ParameterizedTest(name = "Negative input {0} should reject with IllegalArgumentException")
        @ValueSource(longs = {-1L, -100L, -200_000L, Long.MIN_VALUE})
        void shouldRejectNegativeMilliseconds(long negativeMillis) {
            assertThatThrownBy(() -> normalizer.fromMilliseconds(negativeMillis))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be negative");
        }
    }

    @Nested
    @DisplayName("ISO-8601 Conversion Tests")
    class Iso8601ConversionTests {

        @Test
        @DisplayName("Should convert standard PT3M20S duration string")
        void shouldConvertStandardMinuteSecondDuration() {
            Duration result = normalizer.fromIso8601("PT3M20S");

            assertThat(result).isEqualTo(Duration.ofSeconds(200));
            assertThat(result.toMillis()).isEqualTo(200_000L);
        }

        @Test
        @DisplayName("Should convert second-only PT45S duration string")
        void shouldConvertSecondsOnlyDuration() {
            Duration result = normalizer.fromIso8601("PT45S");

            assertThat(result).isEqualTo(Duration.ofSeconds(45));
            assertThat(result.toMillis()).isEqualTo(45_000L);
        }

        @Test
        @DisplayName("Should convert compound PT1H2M3S duration string")
        void shouldConvertHourMinuteSecondDuration() {
            Duration result = normalizer.fromIso8601("PT1H2M3S");

            assertThat(result).isEqualTo(Duration.ofSeconds(3723));
            assertThat(result.toMillis()).isEqualTo(3_723_000L);
        }

        @Test
        @DisplayName("Should convert PT0S to Duration.ZERO")
        void shouldConvertZeroIsoDuration() {
            Duration result = normalizer.fromIso8601("PT0S");

            assertThat(result).isEqualTo(Duration.ZERO);
            assertThat(result.isZero()).isTrue();
        }

        @Test
        @DisplayName("Should convert other zero variants PT0M and PT0H to Duration.ZERO")
        void shouldConvertOtherZeroVariants() {
            assertThat(normalizer.fromIso8601("PT0M")).isEqualTo(Duration.ZERO);
            assertThat(normalizer.fromIso8601("PT0H")).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("Should convert hours-only duration PT1H")
        void shouldConvertHoursOnlyDuration() {
            Duration result = normalizer.fromIso8601("PT1H");

            assertThat(result).isEqualTo(Duration.ofHours(1));
            assertThat(result.toMillis()).isEqualTo(3_600_000L);
        }

        @Test
        @DisplayName("Should convert minutes-only duration PT5M")
        void shouldConvertMinutesOnlyDuration() {
            Duration result = normalizer.fromIso8601("PT5M");

            assertThat(result).isEqualTo(Duration.ofMinutes(5));
            assertThat(result.toMillis()).isEqualTo(300_000L);
        }

        @Test
        @DisplayName("Should convert fractional-second duration preserving decimal precision")
        void shouldConvertFractionalSeconds() {
            Duration result = normalizer.fromIso8601("PT3M20.5S");

            assertThat(result).isEqualTo(Duration.ofSeconds(200, 500_000_000));
            assertThat(result.toMillis()).isEqualTo(200_500L);
            assertThat(result.getNano()).isEqualTo(500_000_000);

            Duration millisOnly = normalizer.fromIso8601("PT0.123S");
            assertThat(millisOnly).isEqualTo(Duration.ofMillis(123));
            assertThat(millisOnly.toMillis()).isEqualTo(123L);
        }

        @Test
        @DisplayName("Should trim whitespace around valid ISO-8601 duration strings")
        void shouldTrimWhitespaceAroundIsoDuration() {
            Duration result = normalizer.fromIso8601("  PT3M20S \t\n ");

            assertThat(result).isEqualTo(Duration.ofSeconds(200));
        }

        @ParameterizedTest(name = "Input \"{0}\" should return null")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "   ", "\t", "\n", "\r\n"})
        void shouldReturnNullForNullOrBlankIsoStrings(String input) {
            assertThat(normalizer.fromIso8601(input)).isNull();
        }

        @ParameterizedTest(name = "Malformed ISO string \"{0}\" should reject with IllegalArgumentException")
        @ValueSource(strings = {"invalid", "PT", "123", "PTX", "P", "PT1Y", "3M20S", "PT--5S"})
        void shouldRejectMalformedIsoStrings(String malformed) {
            assertThatThrownBy(() -> normalizer.fromIso8601(malformed))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid ISO-8601 duration format");
        }

        @ParameterizedTest(name = "Negative ISO string \"{0}\" should reject with IllegalArgumentException")
        @ValueSource(strings = {"-PT3M", "PT-3M", "-PT45S", "PT-20S", "-PT1H"})
        void shouldRejectNegativeIsoStrings(String negativeIso) {
            assertThatThrownBy(() -> normalizer.fromIso8601(negativeIso))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should reject extreme out-of-range ISO-8601 strings causing arithmetic overflow")
        void shouldRejectOverflowingIsoStrings() {
            String extremeDigits = "PT9999999999999999999999999999999999999999H";
            String extremeHours = "PT9000000000000000000H";

            assertThatThrownBy(() -> normalizer.fromIso8601(extremeDigits))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds supported range");

            assertThatThrownBy(() -> normalizer.fromIso8601(extremeHours))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds supported range");
        }
    }

    @Nested
    @DisplayName("Consistency Across Representations")
    class ConsistencyTests {

        @Test
        @DisplayName("Equivalent millisecond and ISO-8601 representations should produce identical Duration")
        void shouldProduceIdenticalDurationForEquivalentRepresentations() {
            assertThat(normalizer.fromMilliseconds(200_000L))
                    .isEqualTo(normalizer.fromIso8601("PT3M20S"));

            assertThat(normalizer.fromMilliseconds(45_000L))
                    .isEqualTo(normalizer.fromIso8601("PT45S"));

            assertThat(normalizer.fromMilliseconds(3_723_000L))
                    .isEqualTo(normalizer.fromIso8601("PT1H2M3S"));

            assertThat(normalizer.fromMilliseconds(0L))
                    .isEqualTo(normalizer.fromIso8601("PT0S"));

            assertThat(normalizer.fromMilliseconds(200_500L))
                    .isEqualTo(normalizer.fromIso8601("PT3M20.5S"));

            assertThat(normalizer.fromMilliseconds(123L))
                    .isEqualTo(normalizer.fromIso8601("PT0.123S"));

            assertThat(normalizer.fromMilliseconds(3_600_000L))
                    .isEqualTo(normalizer.fromIso8601("PT1H"));

            assertThat(normalizer.fromMilliseconds(300_000L))
                    .isEqualTo(normalizer.fromIso8601("PT5M"));
        }
    }

    @Nested
    @DisplayName("Determinism and Purity Tests")
    class DeterminismAndPurityTests {

        @Test
        @DisplayName("Repeated calls with same inputs should return equal results")
        void shouldBeDeterministic() {
            Duration run1 = normalizer.fromMilliseconds(185_000L);
            Duration run2 = normalizer.fromMilliseconds(185_000L);
            Duration run3 = normalizer.fromIso8601("PT3M5S");
            Duration run4 = normalizer.fromIso8601("PT3M5S");

            assertThat(run1).isEqualTo(run2);
            assertThat(run3).isEqualTo(run4);
            assertThat(run1).isEqualTo(run3);
        }

        @Test
        @DisplayName("Separate calls should be isolated and state-free")
        void shouldBeStateless() {
            Duration d1 = normalizer.fromMilliseconds(1000L);
            Duration d2 = normalizer.fromMilliseconds(5000L);
            Duration d3 = normalizer.fromMilliseconds(1000L);

            assertThat(d1).isEqualTo(d3);
            assertThat(d1).isNotEqualTo(d2);
        }
    }

    @Nested
    @DisplayName("Error Contract and Zero Differentiation Tests")
    class ErrorContractTests {

        @Test
        @DisplayName("Invalid inputs should never silently produce Duration.ZERO or fallback durations")
        void invalidInputsShouldNeverProduceZeroDuration() {
            assertThatThrownBy(() -> normalizer.fromMilliseconds(-5L))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> normalizer.fromIso8601("-PT5S"))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> normalizer.fromIso8601("not-a-duration"))
                    .isInstanceOf(IllegalArgumentException.class);

            // Null inputs return null, NEVER Duration.ZERO
            assertThat(normalizer.fromMilliseconds((Long) null)).isNull();
            assertThat(normalizer.fromIso8601(null)).isNull();
            assertThat(normalizer.fromIso8601("")).isNull();
            assertThat(normalizer.fromIso8601("   ")).isNull();
        }

        @Test
        @DisplayName("Legitimate zero durations must be strictly distinct from null")
        void legitimateZeroMustBeStrictlyDistinctFromNull() {
            Duration zeroMillis = normalizer.fromMilliseconds(0L);
            Duration zeroIso = normalizer.fromIso8601("PT0S");

            assertThat(zeroMillis).isNotNull();
            assertThat(zeroIso).isNotNull();
            assertThat(zeroMillis).isEqualTo(Duration.ZERO);
            assertThat(zeroIso).isEqualTo(Duration.ZERO);
        }
    }
}
