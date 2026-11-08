package com.spotifyyoutube.migrator.migration.reliability.scheduling;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring configuration for the durable PostgreSQL scheduler and post-commit worker dispatch (Phase 13D-B and 13D-C).
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(DurableSchedulerProperties.class)
public class DurableSchedulerConfig {
}
