package com.spotifyyoutube.migrator.migration.reliability.scheduling;

import com.spotifyyoutube.migrator.migration.async.MigrationBackgroundWorker;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 13D-C: Worker Dispatch & Executor Saturation Compensation Tests")
class WorkerDispatchAndCompensationTest {

    @Mock
    private MigrationExecutionRepository executionRepository;

    @Mock
    private MigrationBackgroundWorker worker;

    @Mock
    private TaskExecutor taskExecutor;

    @Mock
    private PlatformTransactionManager transactionManager;

    private DurableSchedulerProperties properties;
    private DurableMigrationScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new DurableSchedulerProperties();
        properties.setEnabled(true);
        properties.setLeaseDurationSeconds(60L);
        properties.setRejectionBackoffSeconds(10L);
        properties.setMaxClaimsPerPoll(4);
        properties.setWorkerIdPrefix("test-worker-");

        scheduler = new DurableMigrationScheduler(
                executionRepository,
                worker,
                taskExecutor,
                transactionManager,
                properties
        );
        scheduler.start();
    }

    private void stubTransactionManager() {
        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(txStatus);
    }

    @Nested
    @DisplayName("Post-Commit Dispatch Success")
    class DispatchSuccessTests {

        @Test
        @DisplayName("Worker receives exact execution ID, user ID, and fencing generation")
        void workerReceivesExactFencingGeneration() {
            UUID executionId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            long generation = 42L;
            Instant leaseUntil = Instant.now().plusSeconds(60);
            ClaimedExecution claim = new ClaimedExecution(
                    executionId, userId, generation, leaseUntil, scheduler.getWorkerId());

            ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
            doAnswer(invocation -> {
                Runnable r = invocation.getArgument(0);
                r.run();
                return null;
            }).when(taskExecutor).execute(runnableCaptor.capture());

            boolean dispatched = scheduler.dispatchClaimedExecution(claim);

            assertThat(dispatched).isTrue();
            verify(worker).processExecution(executionId, userId, generation);
            verify(executionRepository, never()).compensateClaimRejection(any(), any(), anyLong(), any());
        }

        @Test
        @DisplayName("Null claim returns false without invoking executor")
        void nullClaimHandledSafely() {
            boolean dispatched = scheduler.dispatchClaimedExecution(null);

            assertThat(dispatched).isFalse();
            verify(taskExecutor, never()).execute(any());
            verify(worker, never()).processExecution(any(), any(), anyLong());
        }
    }

    @Nested
    @DisplayName("Executor Saturation & Compensation")
    class ExecutorSaturationCompensationTests {

        @BeforeEach
        void setUpCompensation() {
            stubTransactionManager();
        }

        @Test
        @DisplayName("TaskRejectedException triggers conditional compensation resetting execution to QUEUED")
        void taskRejectedTriggersCompensation() {
            UUID executionId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            long generation = 7L;
            Instant leaseUntil = Instant.now().plusSeconds(60);
            ClaimedExecution claim = new ClaimedExecution(
                    executionId, userId, generation, leaseUntil, scheduler.getWorkerId());

            doThrow(new TaskRejectedException("Worker queue saturated"))
                    .when(taskExecutor).execute(any(Runnable.class));

            when(executionRepository.compensateClaimRejection(
                    eq(executionId),
                    eq(scheduler.getWorkerId()),
                    eq(generation),
                    any(Instant.class)
            )).thenReturn(1);

            boolean dispatched = scheduler.dispatchClaimedExecution(claim);

            assertThat(dispatched).isFalse();

            ArgumentCaptor<Instant> nextAttemptCaptor = ArgumentCaptor.forClass(Instant.class);
            verify(executionRepository).compensateClaimRejection(
                    eq(executionId),
                    eq(scheduler.getWorkerId()),
                    eq(generation),
                    nextAttemptCaptor.capture()
            );

            // Backoff retry is scheduled in the future
            assertThat(nextAttemptCaptor.getValue()).isAfter(Instant.now().minusSeconds(1));
            verify(worker, never()).processExecution(any(), any(), anyLong());
        }

        @Test
        @DisplayName("Stale compensation protection: compensation updating 0 rows does not throw or corrupt state")
        void staleCompensationReturnsFalse() {
            UUID executionId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            long generation = 1L;
            Instant leaseUntil = Instant.now().plusSeconds(60);
            ClaimedExecution claim = new ClaimedExecution(
                    executionId, userId, generation, leaseUntil, scheduler.getWorkerId());

            // Newer worker took ownership with generation 2, so generation 1 compensation affects 0 rows
            when(executionRepository.compensateClaimRejection(
                    eq(executionId),
                    eq(scheduler.getWorkerId()),
                    eq(generation),
                    any(Instant.class)
            )).thenReturn(0);

            boolean compensated = scheduler.compensateRejection(claim);

            assertThat(compensated).isFalse();
            verify(executionRepository).compensateClaimRejection(
                    eq(executionId),
                    eq(scheduler.getWorkerId()),
                    eq(generation),
                    any(Instant.class)
            );
        }

        @Test
        @DisplayName("Database failure during compensation is observable and caught without throwing")
        void compensationDatabaseFailureHandledGracefully() {
            UUID executionId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            long generation = 3L;
            Instant leaseUntil = Instant.now().plusSeconds(60);
            ClaimedExecution claim = new ClaimedExecution(
                    executionId, userId, generation, leaseUntil, scheduler.getWorkerId());

            when(executionRepository.compensateClaimRejection(
                    any(), any(), anyLong(), any()
            )).thenThrow(new DataAccessResourceFailureException("PostgreSQL connection refused"));

            boolean compensated = scheduler.compensateRejection(claim);

            assertThat(compensated).isFalse();
        }
    }

    @Nested
    @DisplayName("Lifecycle and Capacity Protection")
    class LifecycleAndCapacityTests {

        @Test
        @DisplayName("Stopped scheduler does not claim executions")
        void stoppedSchedulerDoesNotClaim() {
            scheduler.stop();
            assertThat(scheduler.isRunning()).isFalse();

            Optional<ClaimedExecution> claim = scheduler.claimNextExecution();
            assertThat(claim).isEmpty();
            verify(executionRepository, never()).claimNextEligibleExecution(anyString(), anyLong());

            scheduler.pollAndDispatch();
            verify(executionRepository, never()).claimNextEligibleExecution(anyString(), anyLong());
        }

        @Test
        @DisplayName("Disabled scheduler via configuration does not claim")
        void disabledSchedulerDoesNotClaim() {
            properties.setEnabled(false);

            Optional<ClaimedExecution> claim = scheduler.claimNextExecution();
            assertThat(claim).isEmpty();
            verify(executionRepository, never()).claimNextEligibleExecution(anyString(), anyLong());

            scheduler.pollAndDispatch();
            verify(executionRepository, never()).claimNextEligibleExecution(anyString(), anyLong());
        }

        @Test
        @DisplayName("pollAndDispatch halts claiming loop if dispatch was rejected")
        void pollHaltOnRejection() {
            stubTransactionManager();
            UUID executionId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            ClaimedExecution claim = new ClaimedExecution(
                    executionId, userId, 1L, Instant.now().plusSeconds(60), scheduler.getWorkerId());

            when(executionRepository.claimNextEligibleExecution(anyString(), anyLong()))
                    .thenReturn(Optional.of(claim));
            doThrow(new TaskRejectedException("Saturated pool")).when(taskExecutor).execute(any());
            when(executionRepository.compensateClaimRejection(any(), any(), anyLong(), any())).thenReturn(1);

            scheduler.pollAndDispatch();

            // Saturated executor should halt poll loop after first rejected claim instead of claiming maxClaimsPerPoll times
            verify(executionRepository, times(1)).claimNextEligibleExecution(anyString(), anyLong());
            verify(executionRepository, times(1)).compensateClaimRejection(any(), any(), anyLong(), any());
        }

        @Test
        @DisplayName("pollAndDispatch halts when no eligible executions are available")
        void pollHaltOnEmptyClaim() {
            when(executionRepository.claimNextEligibleExecution(anyString(), anyLong()))
                    .thenReturn(Optional.empty());

            scheduler.pollAndDispatch();

            verify(executionRepository, times(1)).claimNextEligibleExecution(anyString(), anyLong());
            verify(taskExecutor, never()).execute(any());
        }
    }
}
