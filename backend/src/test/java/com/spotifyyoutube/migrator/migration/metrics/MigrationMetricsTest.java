package com.spotifyyoutube.migrator.migration.metrics;

import com.spotifyyoutube.migrator.common.metrics.MetricNames;
import com.spotifyyoutube.migrator.common.metrics.TagKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MigrationMetrics Unit Tests")
class MigrationMetricsTest {

    private SimpleMeterRegistry registry;
    private MigrationMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MigrationMetrics(registry);
    }

    @Nested
    @DisplayName("Constructor and Lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("Rejects null MeterRegistry in constructor")
        void rejectsNullRegistry() {
            assertThatThrownBy(() -> new MigrationMetrics(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("MeterRegistry must not be null");
        }

        @Test
        @DisplayName("noop() factory returns working isolated instance")
        void noopFactoryWorks() {
            MigrationMetrics noop = MigrationMetrics.noop();
            noop.recordMigrationStarted("manual");
            noop.recordTrackMatched("excellent");
            noop.recordTrackUnmatched();
        }

        @Test
        @DisplayName("clearDeduplicationState resets in-memory sets")
        void clearDeduplicationStateResets() {
            UUID id = UUID.randomUUID();
            metrics.recordMigrationStarted(id, "manual");
            assertThat(registry.get(MetricNames.MIGRATION_STARTED).counter().count()).isEqualTo(1.0);

            // Duplicate ignored
            metrics.recordMigrationStarted(id, "manual");
            assertThat(registry.get(MetricNames.MIGRATION_STARTED).counter().count()).isEqualTo(1.0);

            // Cleared allows re-record
            metrics.clearDeduplicationState();
            metrics.recordMigrationStarted(id, "manual");
            assertThat(registry.get(MetricNames.MIGRATION_STARTED).counter().count()).isEqualTo(2.0);
        }
    }

    @Nested
    @DisplayName("Migration Lifecycle Counters")
    class LifecycleCounterTests {

        @Test
        @DisplayName("recordMigrationStarted increments counter with mode tag")
        void recordsMigrationStarted() {
            UUID id1 = UUID.randomUUID();
            metrics.recordMigrationStarted(id1, "manual");

            Counter counter = registry.get(MetricNames.MIGRATION_STARTED)
                    .tag(TagKeys.TRIGGER_MODE, "manual")
                    .counter();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("recordMigrationStarted prevents duplicate increments for same execution ID")
        void preventsDuplicateStartedIncrements() {
            UUID id = UUID.randomUUID();
            metrics.recordMigrationStarted(id, "manual");
            metrics.recordMigrationStarted(id, "manual");
            metrics.recordMigrationStarted(id, "automated");

            Counter counter = registry.get(MetricNames.MIGRATION_STARTED).counter();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("recordMigrationStarted records automated trigger mode cleanly")
        void recordsAutomatedMode() {
            UUID id = UUID.randomUUID();
            metrics.recordMigrationStarted(id, "automated");

            Counter counter = registry.get(MetricNames.MIGRATION_STARTED)
                    .tag(TagKeys.TRIGGER_MODE, "automated")
                    .counter();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("recordMigrationCompleted increments counter with outcome and mode tags and records duration")
        void recordsMigrationCompleted() {
            UUID id = UUID.randomUUID();
            Duration duration = Duration.ofSeconds(15);
            metrics.recordMigrationCompleted(id, "completed", "manual", duration);

            Counter counter = registry.get(MetricNames.MIGRATION_COMPLETED)
                    .tag(TagKeys.OUTCOME, "completed")
                    .tag(TagKeys.TRIGGER_MODE, "manual")
                    .counter();
            assertThat(counter.count()).isEqualTo(1.0);

            Timer timer = registry.get(MetricNames.MIGRATION_DURATION)
                    .tag(TagKeys.OUTCOME, "completed")
                    .tag(TagKeys.TRIGGER_MODE, "manual")
                    .timer();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(15.0);
        }

        @Test
        @DisplayName("recordMigrationCompleted supports partially_completed outcome tag")
        void recordsPartiallyCompleted() {
            UUID id = UUID.randomUUID();
            metrics.recordMigrationCompleted(id, "partially_completed", "manual", Duration.ofSeconds(8));

            Counter counter = registry.get(MetricNames.MIGRATION_COMPLETED)
                    .tag(TagKeys.OUTCOME, "partially_completed")
                    .counter();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("recordMigrationCompleted prevents duplicate increments for same execution ID")
        void preventsDuplicateCompletedIncrements() {
            UUID id = UUID.randomUUID();
            metrics.recordMigrationCompleted(id, "completed", "manual", Duration.ofSeconds(10));
            metrics.recordMigrationCompleted(id, "completed", "manual", Duration.ofSeconds(10));

            Counter counter = registry.get(MetricNames.MIGRATION_COMPLETED).counter();
            assertThat(counter.count()).isEqualTo(1.0);

            Timer timer = registry.get(MetricNames.MIGRATION_DURATION).timer();
            assertThat(timer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("recordMigrationFailed increments counter with classified error category")
        void recordsMigrationFailed() {
            UUID id = UUID.randomUUID();
            Duration duration = Duration.ofSeconds(4);
            metrics.recordMigrationFailed(id, "Rate limit exceeded (HTTP 429)", "failed", duration);

            Counter counter = registry.get(MetricNames.MIGRATION_FAILED)
                    .tag(TagKeys.ERROR_CATEGORY, "rate_limit")
                    .tag(TagKeys.OUTCOME, "failed")
                    .counter();
            assertThat(counter.count()).isEqualTo(1.0);

            Timer timer = registry.get(MetricNames.MIGRATION_DURATION)
                    .tag(TagKeys.OUTCOME, "failed")
                    .timer();
            assertThat(timer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("recordMigrationFailed prevents duplicate increments for same execution ID")
        void preventsDuplicateFailedIncrements() {
            UUID id = UUID.randomUUID();
            metrics.recordMigrationFailed(id, "server_error", "failed", Duration.ofSeconds(5));
            metrics.recordMigrationFailed(id, "server_error", "failed", Duration.ofSeconds(5));

            Counter counter = registry.get(MetricNames.MIGRATION_FAILED).counter();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Enforces mutual exclusivity: completed execution cannot later be recorded as failed")
        void completedCannotBecomeFailed() {
            UUID id = UUID.randomUUID();
            metrics.recordMigrationCompleted(id, "completed", "manual", Duration.ofSeconds(10));
            metrics.recordMigrationFailed(id, "server_error", "failed", Duration.ofSeconds(5));

            assertThat(registry.get(MetricNames.MIGRATION_COMPLETED).counter().count()).isEqualTo(1.0);
            assertThat(registry.find(MetricNames.MIGRATION_FAILED).counter()).isNull();
        }

        @Test
        @DisplayName("Enforces mutual exclusivity: failed execution cannot later be recorded as completed")
        void failedCannotBecomeCompleted() {
            UUID id = UUID.randomUUID();
            metrics.recordMigrationFailed(id, "server_error", "failed", Duration.ofSeconds(5));
            metrics.recordMigrationCompleted(id, "completed", "manual", Duration.ofSeconds(10));

            assertThat(registry.get(MetricNames.MIGRATION_FAILED).counter().count()).isEqualTo(1.0);
            assertThat(registry.find(MetricNames.MIGRATION_COMPLETED).counter()).isNull();
        }
    }

    @Nested
    @DisplayName("Track Matching Metrics")
    class MatchingMetricsTests {

        @Test
        @DisplayName("recordTrackMatched increments counter with decision=matched and confidence")
        void recordsTrackMatched() {
            metrics.recordTrackMatched("excellent");
            metrics.recordTrackMatched("good");
            metrics.recordTrackMatched("poor");

            Counter excellentCounter = registry.get(MetricNames.TRACKS_MATCHED)
                    .tag(TagKeys.DECISION, "matched")
                    .tag(TagKeys.CONFIDENCE, "excellent")
                    .counter();
            assertThat(excellentCounter.count()).isEqualTo(1.0);

            Counter goodCounter = registry.get(MetricNames.TRACKS_MATCHED)
                    .tag(TagKeys.DECISION, "matched")
                    .tag(TagKeys.CONFIDENCE, "good")
                    .counter();
            assertThat(goodCounter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("recordTrackMatched normalizes null or blank confidence to 'good'")
        void normalizesBlankConfidence() {
            metrics.recordTrackMatched(null);
            metrics.recordTrackMatched("   ");

            Counter counter = registry.get(MetricNames.TRACKS_MATCHED)
                    .tag(TagKeys.CONFIDENCE, "good")
                    .counter();
            assertThat(counter.count()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("recordTrackUnmatched increments counter with decision=unmatched")
        void recordsTrackUnmatched() {
            metrics.recordTrackUnmatched();
            metrics.recordTrackUnmatched();

            Counter counter = registry.get(MetricNames.TRACKS_UNMATCHED)
                    .tag(TagKeys.DECISION, "unmatched")
                    .counter();
            assertThat(counter.count()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("recordMatchingScore records valid scores in [0.0, 1.0] including boundaries")
        void recordsValidScores() {
            metrics.recordMatchingScore(0.0, "no_match", "poor");
            metrics.recordMatchingScore(0.5, "review_required", "poor");
            metrics.recordMatchingScore(0.85, "matched", "good");
            metrics.recordMatchingScore(1.0, "matched", "excellent");

            DistributionSummary matchedSummary = registry.get(MetricNames.MATCHING_SCORE)
                    .tag(TagKeys.DECISION, "matched")
                    .tag(TagKeys.CONFIDENCE, "good")
                    .summary();
            assertThat(matchedSummary.count()).isEqualTo(1);
            assertThat(matchedSummary.totalAmount()).isEqualTo(0.85);

            DistributionSummary zeroSummary = registry.get(MetricNames.MATCHING_SCORE)
                    .tag(TagKeys.DECISION, "no_match")
                    .tag(TagKeys.CONFIDENCE, "poor")
                    .summary();
            assertThat(zeroSummary.count()).isEqualTo(1);
            assertThat(zeroSummary.totalAmount()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("recordMatchingScore rejects NaN, infinite, negative, and greater than 1.0 values")
        void rejectsInvalidScores() {
            metrics.recordMatchingScore(Double.NaN, "matched", "good");
            metrics.recordMatchingScore(Double.POSITIVE_INFINITY, "matched", "good");
            metrics.recordMatchingScore(Double.NEGATIVE_INFINITY, "matched", "good");
            metrics.recordMatchingScore(-0.1, "matched", "good");
            metrics.recordMatchingScore(1.05, "matched", "good");

            assertThat(registry.find(MetricNames.MATCHING_SCORE).summary()).isNull();
        }
    }

    @Nested
    @DisplayName("Duration Timing")
    class DurationTimingTests {

        @Test
        @DisplayName("recordMigrationDuration records valid duration into timer")
        void recordsDuration() {
            UUID id = UUID.randomUUID();
            metrics.recordMigrationDuration(id, Duration.ofSeconds(12), "completed", "manual");

            Timer timer = registry.get(MetricNames.MIGRATION_DURATION)
                    .tag(TagKeys.OUTCOME, "completed")
                    .tag(TagKeys.TRIGGER_MODE, "manual")
                    .timer();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(12.0);
        }

        @Test
        @DisplayName("recordMigrationDuration clamps negative duration to ZERO")
        void clampsNegativeDuration() {
            metrics.recordMigrationDuration(Duration.ofSeconds(-5), "completed", "manual");

            Timer timer = registry.get(MetricNames.MIGRATION_DURATION).timer();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("recordMigrationDuration ignores null duration")
        void ignoresNullDuration() {
            metrics.recordMigrationDuration(null, "completed", "manual");
            assertThat(registry.find(MetricNames.MIGRATION_DURATION).timer()).isNull();
        }

        @Test
        @DisplayName("recordMigrationDuration prevents duplicate timer records for same execution ID")
        void preventsDuplicateDuration() {
            UUID id = UUID.randomUUID();
            metrics.recordMigrationDuration(id, Duration.ofSeconds(10), "completed", "manual");
            metrics.recordMigrationDuration(id, Duration.ofSeconds(20), "completed", "manual");

            Timer timer = registry.get(MetricNames.MIGRATION_DURATION).timer();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(10.0);
        }
    }

    @Nested
    @DisplayName("Error Category Classification")
    class ErrorClassificationTests {

        @Test
        @DisplayName("Classifies various failure patterns into bounded low-cardinality categories")
        void classifiesErrorCategories() {
            assertThat(MigrationMetrics.classifyErrorCategory(null)).isEqualTo("unknown_error");
            assertThat(MigrationMetrics.classifyErrorCategory("")).isEqualTo("unknown_error");
            assertThat(MigrationMetrics.classifyErrorCategory("HTTP 429 Too Many Requests - rate limit exceeded"))
                    .isEqualTo("rate_limit");
            assertThat(MigrationMetrics.classifyErrorCategory("Daily quota exceeded for YouTube API"))
                    .isEqualTo("quota_exceeded");
            assertThat(MigrationMetrics.classifyErrorCategory("Connection timeout to provider"))
                    .isEqualTo("network_error");
            assertThat(MigrationMetrics.classifyErrorCategory("HTTP 401 Unauthorized token expired"))
                    .isEqualTo("auth_error");
            assertThat(MigrationMetrics.classifyErrorCategory("Invalid request payload validation failed"))
                    .isEqualTo("validation_error");
            assertThat(MigrationMetrics.classifyErrorCategory("Internal server NullPointerException occurred"))
                    .isEqualTo("server_error");
        }
    }
}
