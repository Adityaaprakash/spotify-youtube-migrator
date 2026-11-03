package com.spotifyyoutube.migrator.migration.async;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 12C: AsyncMigrationConfig Bounded ThreadPoolTaskExecutor Test")
class AsyncMigrationConfigTest {

    @Test
    @DisplayName("AsyncMigrationConfig initializes bounded ThreadPoolTaskExecutor with ADR-022 specification")
    void executorConfiguredPerAdr022() {
        AsyncMigrationConfig config = new AsyncMigrationConfig();
        ReflectionTestUtils.setField(config, "corePoolSize", 2);
        ReflectionTestUtils.setField(config, "maxPoolSize", 4);
        ReflectionTestUtils.setField(config, "queueCapacity", 50);
        ReflectionTestUtils.setField(config, "threadNamePrefix", "migration-worker-");
        ReflectionTestUtils.setField(config, "awaitTerminationSeconds", 30);

        ThreadPoolTaskExecutor executor = config.migrationTaskExecutor();

        try {
            assertThat(executor).isNotNull();
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getQueueCapacity()).isEqualTo(50);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("migration-worker-");

            // Verify ThreadPoolExecutor runtime properties
            ThreadPoolExecutor rawExecutor = executor.getThreadPoolExecutor();
            assertThat(rawExecutor.getCorePoolSize()).isEqualTo(2);
            assertThat(rawExecutor.getMaximumPoolSize()).isEqualTo(4);
            assertThat(rawExecutor.getQueue().remainingCapacity()).isEqualTo(50);
            assertThat(rawExecutor.getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);

            // Verify graceful shutdown configuration
            Object waitForTasks = ReflectionTestUtils.getField(executor, "waitForTasksToCompleteOnShutdown");
            assertThat(waitForTasks).isEqualTo(true);
            Object awaitTermination = ReflectionTestUtils.getField(executor, "awaitTerminationMillis");
            assertThat(awaitTermination).isEqualTo(30_000L);
        } finally {
            executor.shutdown();
        }
    }
}
