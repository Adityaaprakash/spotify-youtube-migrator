package com.spotifyyoutube.migrator.migration.async;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Spring configuration for the asynchronous migration background worker engine (Phase 12C).
 * <p>
 * Configures a bounded {@link ThreadPoolTaskExecutor} according to ADR-022:
 * <ul>
 *   <li>Core pool size: 2</li>
 *   <li>Max pool size: 4</li>
 *   <li>Queue capacity: 50</li>
 *   <li>Thread prefix: "migration-worker-"</li>
 *   <li>Rejection policy: {@link ThreadPoolExecutor.AbortPolicy}</li>
 *   <li>Graceful shutdown: waitForTasksToCompleteOnShutdown = true, awaitTerminationSeconds = 30</li>
 * </ul>
 * <p>
 * <strong>ThreadPoolExecutor Behavior:</strong>
 * A bounded {@link ThreadPoolExecutor} operates as follows:
 * <ol>
 *   <li>Starts worker threads up to {@code corePoolSize} as tasks arrive.</li>
 *   <li>When all core threads are busy, buffers incoming tasks into the bounded queue up to {@code queueCapacity}.</li>
 *   <li>When the bounded queue is completely full, scales worker threads up to {@code maxPoolSize}.</li>
 *   <li>When both maximum pool size and queue capacity are exhausted (54 concurrent migrations), rejects
 *       further submissions via {@link ThreadPoolExecutor.AbortPolicy}, throwing {@link org.springframework.core.task.TaskRejectedException}.</li>
 * </ol>
 */
@Configuration
public class AsyncMigrationConfig {

    public static final String MIGRATION_TASK_EXECUTOR_BEAN = "migrationTaskExecutor";

    @Value("${migration.async.core-pool-size:2}")
    private int corePoolSize;

    @Value("${migration.async.max-pool-size:4}")
    private int maxPoolSize;

    @Value("${migration.async.queue-capacity:50}")
    private int queueCapacity;

    @Value("${migration.async.thread-name-prefix:migration-worker-}")
    private String threadNamePrefix;

    @Value("${migration.async.await-termination-seconds:30}")
    private int awaitTerminationSeconds;

    @Bean(name = MIGRATION_TASK_EXECUTOR_BEAN)
    public ThreadPoolTaskExecutor migrationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        executor.initialize();
        return executor;
    }
}
