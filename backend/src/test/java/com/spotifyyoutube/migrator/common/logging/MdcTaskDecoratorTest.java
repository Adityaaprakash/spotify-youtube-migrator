package com.spotifyyoutube.migrator.common.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MdcTaskDecorator Tests (Phase 18F)")
class MdcTaskDecoratorTest {

    private MdcTaskDecorator decorator;
    private ExecutorService singleThreadExecutor;

    @BeforeEach
    void setUp() {
        MDC.clear();
        decorator = new MdcTaskDecorator();
        singleThreadExecutor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        MDC.clear();
        singleThreadExecutor.shutdownNow();
        singleThreadExecutor.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Propagates caller MDC context to worker thread")
    void propagatesCallerMdcToWorkerThread() throws Exception {
        MDC.put(LoggingConstants.MDC_REQUEST_ID, "req-async-123");
        MDC.put(LoggingConstants.MDC_APPLICATION, "migrator");

        AtomicReference<String> workerRequestId = new AtomicReference<>();
        AtomicReference<String> workerApplication = new AtomicReference<>();

        Runnable task = () -> {
            workerRequestId.set(MDC.get(LoggingConstants.MDC_REQUEST_ID));
            workerApplication.set(MDC.get(LoggingConstants.MDC_APPLICATION));
        };

        Runnable decorated = decorator.decorate(task);
        Future<?> future = singleThreadExecutor.submit(decorated);
        future.get(2, TimeUnit.SECONDS);

        assertThat(workerRequestId.get()).isEqualTo("req-async-123");
        assertThat(workerApplication.get()).isEqualTo("migrator");
    }

    @Test
    @DisplayName("Cleans up worker thread MDC after execution completes")
    void cleansUpWorkerThreadMdcAfterExecution() throws Exception {
        MDC.put(LoggingConstants.MDC_REQUEST_ID, "req-cleanup-async");

        Runnable task = () -> assertThat(MDC.get(LoggingConstants.MDC_REQUEST_ID)).isEqualTo("req-cleanup-async");
        Runnable decorated = decorator.decorate(task);

        singleThreadExecutor.submit(decorated).get(2, TimeUnit.SECONDS);

        // Verify the background thread itself has no leftover MDC
        AtomicReference<String> leftoverMdc = new AtomicReference<>();
        singleThreadExecutor.submit(() -> leftoverMdc.set(MDC.get(LoggingConstants.MDC_REQUEST_ID))).get(2, TimeUnit.SECONDS);

        assertThat(leftoverMdc.get()).isNull();
    }

    @Test
    @DisplayName("Cleans up worker thread MDC even when task throws an exception")
    void cleansUpWorkerThreadMdcOnException() throws Exception {
        MDC.put(LoggingConstants.MDC_REQUEST_ID, "req-exception-async");

        Runnable task = () -> {
            assertThat(MDC.get(LoggingConstants.MDC_REQUEST_ID)).isEqualTo("req-exception-async");
            throw new RuntimeException("Async task failed intentionally");
        };

        Runnable decorated = decorator.decorate(task);

        Future<?> future = singleThreadExecutor.submit(decorated);
        try {
            future.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException expected) {
            // expected
        }

        // Verify the background thread was cleaned up
        AtomicReference<String> leftoverMdc = new AtomicReference<>();
        singleThreadExecutor.submit(() -> leftoverMdc.set(MDC.get(LoggingConstants.MDC_REQUEST_ID))).get(2, TimeUnit.SECONDS);

        assertThat(leftoverMdc.get()).isNull();
    }

    @Test
    @DisplayName("Worker thread has empty MDC when caller thread has no MDC")
    void cleanWorkerThreadWhenCallerHasNoMdc() throws Exception {
        MDC.clear();

        AtomicReference<String> workerRequestId = new AtomicReference<>();
        Runnable task = () -> workerRequestId.set(MDC.get(LoggingConstants.MDC_REQUEST_ID));

        Runnable decorated = decorator.decorate(task);
        singleThreadExecutor.submit(decorated).get(2, TimeUnit.SECONDS);

        assertThat(workerRequestId.get()).isNull();
    }

    @Test
    @DisplayName("Reused worker pool threads do not leak context across multiple sequential tasks")
    void reusedWorkerThreadDoesNotLeakContext() throws Exception {
        for (int i = 1; i <= 5; i++) {
            MDC.clear();
            MDC.put(LoggingConstants.MDC_REQUEST_ID, "sequential-task-" + i);

            final int taskIndex = i;
            AtomicReference<String> actualRequestId = new AtomicReference<>();
            AtomicReference<String> previousStaleKey = new AtomicReference<>();

            Runnable task = () -> {
                actualRequestId.set(MDC.get(LoggingConstants.MDC_REQUEST_ID));
                if (taskIndex > 1) {
                    previousStaleKey.set(MDC.get("customKey-" + (taskIndex - 1)));
                }
                MDC.put("customKey-" + taskIndex, "customValue-" + taskIndex);
            };

            Runnable decorated = decorator.decorate(task);
            singleThreadExecutor.submit(decorated).get(2, TimeUnit.SECONDS);

            assertThat(actualRequestId.get()).isEqualTo("sequential-task-" + i);
            if (taskIndex > 1) {
                assertThat(previousStaleKey.get()).isNull();
            }
        }
    }
}
