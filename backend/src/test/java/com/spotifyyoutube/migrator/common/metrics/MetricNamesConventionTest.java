package com.spotifyyoutube.migrator.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 18C: Metric Naming Convention & Prometheus Export Tests")
class MetricNamesConventionTest {

    private static final Pattern MICROMETER_NAME_PATTERN = Pattern.compile("^[a-z]+(\\.[a-z]+)+$");

    @Test
    @DisplayName("All 9 roadmap metrics are mapped to valid internal Micrometer names")
    void allRoadmapMetricsMapped() {
        Map<String, String> mapping = MetricNames.getRoadmapMapping();

        assertThat(mapping).containsKeys(
                "migration_started_total",
                "migration_completed_total",
                "migration_failed_total",
                "tracks_matched_total",
                "tracks_unmatched_total",
                "youtube_api_requests_total",
                "youtube_api_errors_total",
                "average_match_score",
                "migration_duration"
        );

        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            assertThat(entry.getValue())
                    .as("Micrometer name for roadmap metric %s", entry.getKey())
                    .isNotBlank()
                    .matches(MICROMETER_NAME_PATTERN);
        }
    }

    @Test
    @DisplayName("PrometheusMeterRegistry exports Counter metrics with standard _total suffix")
    void prometheusCounterExportConvention() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        Counter counter = registry.counter(MetricNames.MIGRATION_STARTED);
        counter.increment();

        String scrape = registry.scrape();
        assertThat(scrape).contains("migration_started_total 1.0");
    }

    @Test
    @DisplayName("PrometheusMeterRegistry exports Timer metrics with standard _seconds suffix")
    void prometheusTimerExportConvention() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        Timer timer = registry.timer(MetricNames.MIGRATION_DURATION);
        timer.record(java.time.Duration.ofSeconds(5));

        String scrape = registry.scrape();
        assertThat(scrape).contains("migration_duration_seconds_count 1");
        assertThat(scrape).contains("migration_duration_seconds_sum 5.0");
    }

    @Test
    @DisplayName("PrometheusMeterRegistry exports DistributionSummary with count and sum for average derivation")
    void prometheusDistributionSummaryExportConvention() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        DistributionSummary summary = DistributionSummary.builder(MetricNames.MATCHING_SCORE)
                .minimumExpectedValue(0.01)
                .maximumExpectedValue(1.0)
                .register(registry);

        summary.record(0.75);
        summary.record(0.25);

        String scrape = registry.scrape();
        assertThat(scrape).contains("matching_score_count 2");
        assertThat(scrape).contains("matching_score_sum 1.0");
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.mean()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("MetricNames.toPrometheusBaseName provides expected Prometheus base names")
    void toPrometheusBaseNameMapping() {
        assertThat(MetricNames.toPrometheusBaseName(MetricNames.MIGRATION_STARTED))
                .isEqualTo("migration_started_total");
        assertThat(MetricNames.toPrometheusBaseName(MetricNames.MIGRATION_DURATION))
                .isEqualTo("migration_duration_seconds");
        assertThat(MetricNames.toPrometheusBaseName(MetricNames.MATCHING_SCORE))
                .isEqualTo("matching_score");
    }
}
