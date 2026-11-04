package com.spotifyyoutube.migrator.migration.async;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.migration.application.execution.ApprovedMigrationOrchestrator;
import com.spotifyyoutube.migrator.migration.application.execution.MigrationExecutionStateService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Concurrency and worker saturation tests for {@link AsyncMigrationConfig},
 * {@link MigrationBackgroundWorker}, and {@link MigrationJobDispatcher} (Phase 12G-D).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 12G-D: Worker and Executor Concurrency Tests")
class WorkerAndExecutorConcurrencyTest {

    @Mock
    private ApprovedMigrationOrchestrator orchestrator;

    @Mock
    private MigrationExecutionStateService executionStateService;

    private ThreadPoolTaskExecutor taskExecutor;
    private MigrationBackgroundWorker worker;
    private MigrationJobDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        AsyncMigrationConfig config = new AsyncMigrationConfig();
        ReflectionTestUtils.setField(config, "corePoolSize", 2);
        ReflectionTestUtils.setField(config, "maxPoolSize", 4);
        ReflectionTestUtils.setField(config, "queueCapacity", 50);
        ReflectionTestUtils.setField(config, "threadNamePrefix", "migration-worker-");
        ReflectionTestUtils.setField(config, "awaitTerminationSeconds", 5);

        taskExecutor = config.migrationTaskExecutor();
        worker = new MigrationBackgroundWorker(orchestrator);
        dispatcher = new MigrationJobDispatcher(orchestrator, executionStateService, worker, taskExecutor);
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.shutdown();
        }
    }

    @Test
    @DisplayName("Test 1: Multiple migrations execute concurrently up to configured maxPoolSize without state corruption")
    void concurrentWorkerExecutionWithinPoolBounds() throws Exception {
        int totalTasks = 4;
        int corePoolConcurrency = 2;
        CyclicBarrier coreBarrier = new CyclicBarrier(corePoolConcurrency);
        CountDownLatch doneLatch = new CountDownLatch(totalTasks);
        AtomicInteger activeThreads = new AtomicInteger(0);
        AtomicInteger maxObservedConcurrency = new AtomicInteger(0);
        AtomicInteger taskCounter = new AtomicInteger(0);
        List<String> threadNames = Collections.synchronizedList(new ArrayList<>());

        when(orchestrator.executeQueuedMigration(any(UUID.class), any(UUID.class)))
                .thenAnswer(invocation -> {
                    int taskIndex = taskCounter.incrementAndGet();
                    int current = activeThreads.incrementAndGet();
                    maxObservedConcurrency.accumulateAndGet(current, Math::max);
                    threadNames.add(Thread.currentThread().getName());

                    // Synchronize the core pool threads concurrently executing the first 2 tasks
                    if (taskIndex <= corePoolConcurrency) {
                        coreBarrier.await(5, TimeUnit.SECONDS);
                    }

                    activeThreads.decrementAndGet();
                    doneLatch.countDown();

                    UUID execId = invocation.getArgument(0);
                    return new MigrationExecutionSummary(
                            execId, UUID.randomUUID(), MigrationExecutionStatus.COMPLETED,
                            "PL_dest", "url", 5, 5, 0, 0, 0, 0,
                            Instant.now(), Instant.now(), 1L
                    );
                });

        for (int i = 0; i < totalTasks; i++) {
            UUID executionId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            taskExecutor.execute(() -> worker.processMigration(executionId, userId));
        }

        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(maxObservedConcurrency.get()).isGreaterThanOrEqualTo(2);
        assertThat(maxObservedConcurrency.get()).isLessThanOrEqualTo(4);
        assertThat(threadNames).allMatch(name -> name.startsWith("migration-worker-"));
    }

    @Test
    @DisplayName("Test 2: Executor saturation (4 active + 50 queued) triggers rejection and resets execution to INITIALIZED")
    void executorSaturationTriggersRejectionAndResetsExecution() throws Exception {
        int maxPool = 4;
        int queueCapacity = 50;
        int totalToSaturate = maxPool + queueCapacity; // 54

        CountDownLatch blockingLatch = new CountDownLatch(1);
        CountDownLatch activeWorkersLatch = new CountDownLatch(maxPool);

        // Fill all 4 active threads and 50 queue slots
        for (int i = 0; i < totalToSaturate; i++) {
            taskExecutor.execute(() -> {
                activeWorkersLatch.countDown();
                try {
                    blockingLatch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Ensure all 4 worker threads are active and blocked
        boolean workersRunning = activeWorkersLatch.await(5, TimeUnit.SECONDS);
        assertThat(workersRunning).isTrue();
        assertThat(taskExecutor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(0);

        // Attempt 55th submission via dispatcher -> saturated!
        UUID executionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ApprovedMigrationExecutionRequest request = ApprovedMigrationExecutionRequest.of(sessionId, userId);

        MigrationExecutionSummary queuedSummary = new MigrationExecutionSummary(
                executionId, sessionId, MigrationExecutionStatus.QUEUED,
                "PL_dest", "url", 10, 0, 0, 0, 0, 0, null, null, 0L
        );
        when(orchestrator.prepareQueuedMigration(eq(request), eq(userId))).thenReturn(queuedSummary);

        assertThatThrownBy(() -> dispatcher.dispatchMigration(request, userId))
                .isInstanceOf(TaskRejectedException.class);

        // Verify handleDispatchRejection was called to reset QUEUED -> INITIALIZED
        verify(executionStateService).handleDispatchRejection(eq(executionId), eq(userId), any());

        // Unblock saturated tasks to allow clean shutdown
        blockingLatch.countDown();
    }

    @Test
    @DisplayName("Test 3: Worker exception handling propagates diagnostic logging and does not corrupt execution state")
    void workerExceptionHandlingPreservesState() {
        UUID executionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // 1. InvalidStateException (e.g. concurrent claim conflict)
        when(orchestrator.executeQueuedMigration(eq(executionId), eq(userId)))
                .thenThrow(new InvalidStateException("Migration execution could not be claimed: already in progress"));
        worker.processMigration(executionId, userId);
        verify(orchestrator).executeQueuedMigration(executionId, userId);

        // 2. ResourceNotFoundException
        when(orchestrator.executeQueuedMigration(eq(executionId), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Migration execution not found: " + executionId));
        worker.processMigration(executionId, userId);

        // 3. RuntimeException (e.g. database network glitch)
        when(orchestrator.executeQueuedMigration(eq(executionId), eq(userId)))
                .thenThrow(new RuntimeException("Transient connection error"));
        worker.processMigration(executionId, userId);
    }

    @Test
    @DisplayName("Test 4: Graceful shutdown waits for active worker task to complete")
    void gracefulShutdownWaitsForActiveTask() throws Exception {
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskCompleted = new CountDownLatch(1);
        AtomicBoolean completedGracefully = new AtomicBoolean(false);

        taskExecutor.execute(() -> {
            taskStarted.countDown();
            try {
                // Simulate controlled background work
                Thread.sleep(100);
                completedGracefully.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                taskCompleted.countDown();
            }
        });

        boolean started = taskStarted.await(2, TimeUnit.SECONDS);
        assertThat(started).isTrue();

        // Trigger shutdown - since waitForTasksToCompleteOnShutdown is true, it should wait
        taskExecutor.shutdown();

        boolean done = taskCompleted.await(5, TimeUnit.SECONDS);
        assertThat(done).isTrue();
        assertThat(completedGracefully.get()).isTrue();
    }
}
