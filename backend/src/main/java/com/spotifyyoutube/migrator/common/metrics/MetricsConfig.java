package com.spotifyyoutube.migrator.common.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration providing Micrometer meter filters and registry defaults.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsConfig {

    /**
     * Binds a standard application tag to all registered meters across all registries.
     */
    @Bean
    public MeterFilter commonTagsMeterFilter() {
        return MeterFilter.commonTags(Tags.of("application", "spotify-youtube-migrator"));
    }

    /**
     * Configures statistical distribution boundaries for matching score histograms.
     */
    @Bean
    public MeterFilter matchingScoreMeterFilter() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (MetricNames.MATCHING_SCORE.equals(id.getName())) {
                    return DistributionStatisticConfig.builder()
                            .minimumExpectedValue(0.01)
                            .maximumExpectedValue(1.0)
                            .build()
                            .merge(config);
                }
                return config;
            }
        };
    }
}
