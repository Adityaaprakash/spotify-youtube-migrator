package com.spotifyyoutube.migrator.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Phase 18C: MetricsRegistrationHelper Unit Tests")
class MetricsRegistrationHelperTest {

    private MeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    @Nested
    @DisplayName("Counter Registration")
    class CounterTests {

        @Test
        @DisplayName("Registers counter with tags and description cleanly")
        void registersCounter() {
            Tags tags = TagPolicy.tags(TagKeys.PROVIDER, "spotify");
            Counter counter = MetricsRegistrationHelper.counter(
                    registry, MetricNames.SPOTIFY_API_REQUESTS, "Spotify API requests", tags);

            assertThat(counter).isNotNull();
            assertThat(counter.getId().getName()).isEqualTo(MetricNames.SPOTIFY_API_REQUESTS);
            assertThat(counter.getId().getDescription()).isEqualTo("Spotify API requests");
            assertThat(counter.getId().getTag(TagKeys.PROVIDER)).isEqualTo("spotify");
        }

        @Test
        @DisplayName("Repeated registration returns the same counter instance without duplication")
        void idempotentRegistration() {
            Tags tags = TagPolicy.tags(TagKeys.PROVIDER, "youtube");
            Counter counter1 = MetricsRegistrationHelper.counter(
                    registry, MetricNames.YOUTUBE_API_REQUESTS, "YouTube requests", tags);
            Counter counter2 = MetricsRegistrationHelper.counter(
                    registry, MetricNames.YOUTUBE_API_REQUESTS, "YouTube requests", tags);

            assertThat(counter1).isSameAs(counter2);
        }
    }

    @Nested
    @DisplayName("Timer Registration")
    class TimerTests {

        @Test
        @DisplayName("Registers timer with tags and description cleanly")
        void registersTimer() {
            Tags tags = TagPolicy.tags(TagKeys.OUTCOME, "completed");
            Timer timer = MetricsRegistrationHelper.timer(
                    registry, MetricNames.MIGRATION_DURATION, "Migration execution duration", tags);

            assertThat(timer).isNotNull();
            assertThat(timer.getId().getName()).isEqualTo(MetricNames.MIGRATION_DURATION);
            assertThat(timer.getId().getTag(TagKeys.OUTCOME)).isEqualTo("completed");
        }
    }

    @Nested
    @DisplayName("DistributionSummary Registration")
    class DistributionSummaryTests {

        @Test
        @DisplayName("Registers DistributionSummary with expected bounds and tags")
        void registersDistributionSummary() {
            Tags tags = TagPolicy.tags(TagKeys.DECISION, "matched");
            DistributionSummary summary = MetricsRegistrationHelper.distributionSummary(
                    registry, MetricNames.MATCHING_SCORE, "Track match confidence scores", tags, 0.01, 1.0);

            assertThat(summary).isNotNull();
            assertThat(summary.getId().getName()).isEqualTo(MetricNames.MATCHING_SCORE);
            assertThat(summary.getId().getTag(TagKeys.DECISION)).isEqualTo("matched");
        }
    }

    @Nested
    @DisplayName("Validation and Boundary Guardrails")
    class ValidationTests {

        @Test
        @DisplayName("Rejects null MeterRegistry")
        void rejectsNullRegistry() {
            assertThatThrownBy(() -> MetricsRegistrationHelper.counter(null, "test.counter", "desc", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("MeterRegistry must not be null");
        }

        @Test
        @DisplayName("Rejects null or blank metric name")
        void rejectsBlankName() {
            assertThatThrownBy(() -> MetricsRegistrationHelper.counter(registry, "  ", "desc", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Metric name must not be null or blank");
        }

        @Test
        @DisplayName("Enforces TagPolicy on registration: rejects prohibited tag key")
        void rejectsProhibitedTag() {
            Tags unsafeTags = Tags.of("userId", "user-123");
            assertThatThrownBy(() -> MetricsRegistrationHelper.counter(
                    registry, "test.counter", "desc", unsafeTags))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is prohibited due to high cardinality risk");
        }

        @Test
        @DisplayName("Enforces TagPolicy on registration: rejects UUID tag value")
        void rejectsUuidTagValue() {
            Tags unsafeTags = Tags.of("provider", "550e8400-e29b-41d4-a716-446655440000");
            assertThatThrownBy(() -> MetricsRegistrationHelper.counter(
                    registry, "test.counter", "desc", unsafeTags))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is a UUID, which causes high cardinality");
        }
    }
}
