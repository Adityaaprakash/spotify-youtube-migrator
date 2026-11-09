package com.spotifyyoutube.migrator.migration.reliability.scheduling;

import com.spotifyyoutube.migrator.migration.async.MigrationBackgroundWorker;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Phase 13D-F: Durable Scheduler Lifecycle & Hardening Unit Test")
class DurableSchedulerLifecycleAndHardeningTest {

    private MigrationExecutionRepository executionRepository;
    private MigrationBackgroundWorker worker;
    private TaskExecutor taskExecutor;
    private PlatformTransactionManager transactionManager;
    private DurableSchedulerProperties properties;
    private DurableLeaseHeartbeatService heartbeatService;

    private DurableMigrationScheduler scheduler;

    @BeforeEach
    void setUp() {
        executionRepository = mock(MigrationExecutionRepository.class);
        worker = mock(MigrationBackgroundWorker.class);
        taskExecutor = mock(TaskExecutor.class);
        transactionManager = mock(PlatformTransactionManager.class);
        properties = new DurableSchedulerProperties();
        properties.setEnabled(true);
        properties.setMaxClaimsPerPoll(5);
        properties.setShutdownGracePeriodSeconds(2L);
        heartbeatService = mock(DurableLeaseHeartbeatService.class);

        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        scheduler = new DurableMigrationScheduler(
                executionRepository,
                worker,
                taskExecutor,
                transactionManager,
                properties,
                heartbeatService
        );
    }

    @Nested
    @DisplayName("Graceful Shutdown Lifecycle")
    class ShutdownTests {

        @Test
        @DisplayName("stop() sets running to false and stops new claims")
        void stopHaltsScheduler() {
            scheduler.start();
            assertThat(scheduler.isRunning()).isTrue();

            scheduler.stop();
            assertThat(scheduler.isRunning()).isFalse();

            scheduler.pollAndDispatch();
            verify(executionRepository, never()).claimNextEligibleExecution(anyString(), anyLong());
        }

        @Test
        @DisplayName("stop(callback) completes immediately when 0 active workers are registered")
        void stopWithZeroActiveWorkers() {
            scheduler.start();
            when(heartbeatService.getActiveLeaseCount()).thenReturn(0);

            AtomicBoolean callbackRan = new AtomicBoolean(false);
            scheduler.stop(() -> callbackRan.set(true));

            assertThat(scheduler.isRunning()).isFalse();
            assertThat(callbackRan.get()).isTrue();
        }

        @Test
        @DisplayName("stop(callback) waits for active workers to complete within grace period")
        void stopWaitsForActiveWorkers() {
            scheduler.start();
            when(heartbeatService.getActiveLeaseCount())
                    .thenReturn(1)
                    .thenReturn(1)
                    .thenReturn(0);

            AtomicBoolean callbackRan = new AtomicBoolean(false);
            long start = System.currentTimeMillis();
            scheduler.stop(() -> callbackRan.set(true));
            long elapsed = System.currentTimeMillis() - start;

            assertThat(scheduler.isRunning()).isFalse();
            assertThat(callbackRan.get()).isTrue();
            assertThat(elapsed).isLessThan(2000L);
        }
    }

    @Nested
    @DisplayName("Saturation and Error Hardening")
    class HardeningTests {

        @Test
        @DisplayName("pollAndDispatch pauses when ThreadPoolTaskExecutor queue is full")
        void executorSaturationPausesPolling() {
            ThreadPoolTaskExecutor tpte = mock(ThreadPoolTaskExecutor.class);
            ThreadPoolExecutor tpe = mock(ThreadPoolExecutor.class);
            @SuppressWarnings("unchecked")
            java.util.concurrent.BlockingQueue<Runnable> queue = mock(java.util.concurrent.BlockingQueue.class);

            when(tpte.getThreadPoolExecutor()).thenReturn(tpe);
            when(tpe.getQueue()).thenReturn(queue);
            when(queue.remainingCapacity()).thenReturn(0); // Saturated!

            DurableMigrationScheduler saturatedScheduler = new DurableMigrationScheduler(
                    executionRepository, worker, tpte, transactionManager, properties, heartbeatService
            );
            saturatedScheduler.start();

            saturatedScheduler.pollAndDispatch();

            verify(executionRepository, never()).claimNextEligibleExecution(anyString(), anyLong());
        }

        @Test
        @DisplayName("pollAndDispatch recovers from transient database errors without throwing")
        void transientDatabaseErrorDoesNotThrow() {
            scheduler.start();
            when(executionRepository.claimNextEligibleExecution(anyString(), anyLong()))
                    .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("PostgreSQL connection lost"));

            // Must not throw exception out of scheduled polling
            scheduler.pollAndDispatch();

            verify(worker, never()).processExecution(any(), any(), anyLong());
        }

        @Test
        @DisplayName("compensateRejection safely updates database with backoff timestamp")
        void compensateRejectionSuccess() {
            UUID execId = UUID.randomUUID();
            ClaimedExecution claim = new ClaimedExecution(
                    execId, UUID.randomUUID(), 1L, "node-1", Instant.now().plusSeconds(60)
            );

            when(executionRepository.compensateClaimRejection(eq(execId), eq("node-1"), eq(1L), any(Instant.class)))
                    .thenReturn(1);

            boolean result = scheduler.compensateRejection(claim);

            assertThat(result).isTrue();
            verify(executionRepository).compensateClaimRejection(eq(execId), eq("node-1"), eq(1L), any(Instant.class));
        }

        @Test
        @DisplayName("compensateRejection handles database exception safely")
        void compensateRejectionHandlesDbException() {
            UUID execId = UUID.randomUUID();
            ClaimedExecution claim = new ClaimedExecution(
                    execId, UUID.randomUUID(), 1L, "node-1", Instant.now().plusSeconds(60)
            );

            when(executionRepository.compensateClaimRejection(any(), any(), anyLong(), any()))
                    .thenThrow(new RuntimeException("DB offline"));

            boolean result = scheduler.compensateRejection(claim);

            assertThat(result).isFalse();
        }
    }
}
