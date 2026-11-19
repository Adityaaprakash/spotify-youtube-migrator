package com.spotifyyoutube.migrator.migration.metrics;

import com.spotifyyoutube.migrator.common.metrics.MetricNames;
import com.spotifyyoutube.migrator.common.metrics.TagKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "management.prometheus.metrics.export.enabled=true",
        "migration.scheduler.enabled=false"
})
@DisplayName("Migration Business Metrics Spring Context Integration Tests")
class MigrationBusinessMetricsIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private MigrationMetrics migrationMetrics;

    @BeforeEach
    void setUp() {
        migrationMetrics.clearDeduplicationState();
    }

    @Test
    @DisplayName("Spring context injects MeterRegistry and MigrationMetrics beans cleanly")
    void contextLoadsMetricsBeans() {
        assertThat(meterRegistry).isNotNull();
        assertThat(migrationMetrics).isNotNull();
    }

    @Test
    @DisplayName("Full lifecycle recording in Spring-managed registry adheres to conventions and tags")
    void recordsFullLifecycleInSpringContext() {
        UUID executionId = UUID.randomUUID();

        // 1. Migration started
        migrationMetrics.recordMigrationStarted(executionId, "manual");
        Counter startedCounter = meterRegistry.get(MetricNames.MIGRATION_STARTED)
                .tag("application", "spotify-youtube-migrator")
                .tag(TagKeys.TRIGGER_MODE, "manual")
                .counter();
        assertThat(startedCounter.count()).isGreaterThanOrEqualTo(1.0);

        // 2. Track matching and scores
        migrationMetrics.recordTrackMatched("excellent");
        Counter matchedCounter = meterRegistry.get(MetricNames.TRACKS_MATCHED)
                .tag("application", "spotify-youtube-migrator")
                .tag(TagKeys.DECISION, "matched")
                .tag(TagKeys.CONFIDENCE, "excellent")
                .counter();
        assertThat(matchedCounter.count()).isGreaterThanOrEqualTo(1.0);

        migrationMetrics.recordTrackUnmatched();
        Counter unmatchedCounter = meterRegistry.get(MetricNames.TRACKS_UNMATCHED)
                .tag("application", "spotify-youtube-migrator")
                .tag(TagKeys.DECISION, "unmatched")
                .counter();
        assertThat(unmatchedCounter.count()).isGreaterThanOrEqualTo(1.0);

        migrationMetrics.recordMatchingScore(0.92, "matched", "excellent");
        DistributionSummary summary = meterRegistry.get(MetricNames.MATCHING_SCORE)
                .tag("application", "spotify-youtube-migrator")
                .tag(TagKeys.DECISION, "matched")
                .summary();
        assertThat(summary.count()).isGreaterThanOrEqualTo(1);

        // 3. Migration completed with duration
        Duration duration = Duration.ofSeconds(20);
        migrationMetrics.recordMigrationCompleted(executionId, "completed", "manual", duration);

        Counter completedCounter = meterRegistry.get(MetricNames.MIGRATION_COMPLETED)
                .tag("application", "spotify-youtube-migrator")
                .tag(TagKeys.OUTCOME, "completed")
                .counter();
        assertThat(completedCounter.count()).isGreaterThanOrEqualTo(1.0);

        Timer durationTimer = meterRegistry.get(MetricNames.MIGRATION_DURATION)
                .tag("application", "spotify-youtube-migrator")
                .tag(TagKeys.OUTCOME, "completed")
                .timer();
        assertThat(durationTimer.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Duplicate lifecycle recording for the same execution is safely ignored")
    void duplicateLifecycleExecutionIsIgnored() {
        UUID executionId = UUID.randomUUID();

        migrationMetrics.recordMigrationStarted(executionId, "manual");
        double startCount1 = meterRegistry.get(MetricNames.MIGRATION_STARTED).counter().count();

        migrationMetrics.recordMigrationStarted(executionId, "manual");
        double startCount2 = meterRegistry.get(MetricNames.MIGRATION_STARTED).counter().count();
        assertThat(startCount2).isEqualTo(startCount1);

        migrationMetrics.recordMigrationCompleted(executionId, "completed", "manual", Duration.ofSeconds(10));
        double completeCount1 = meterRegistry.get(MetricNames.MIGRATION_COMPLETED).counter().count();

        migrationMetrics.recordMigrationCompleted(executionId, "completed", "manual", Duration.ofSeconds(10));
        double completeCount2 = meterRegistry.get(MetricNames.MIGRATION_COMPLETED).counter().count();
        assertThat(completeCount2).isEqualTo(completeCount1);
    }
}
