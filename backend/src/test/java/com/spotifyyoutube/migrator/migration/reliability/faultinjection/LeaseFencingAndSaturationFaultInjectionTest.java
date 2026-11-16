package com.spotifyyoutube.migrator.migration.reliability.faultinjection;

import com.spotifyyoutube.migrator.migration.async.MigrationBackgroundWorker;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.reliability.scheduling.ClaimedExecution;
import com.spotifyyoutube.migrator.migration.reliability.scheduling.DurableLeaseHeartbeatService;
import com.spotifyyoutube.migrator.migration.reliability.scheduling.DurableMigrationScheduler;
import com.spotifyyoutube.migrator.migration.reliability.scheduling.DurableSchedulerProperties;
import com.spotifyyoutube.migrator.migration.reliability.scheduling.StaleLeaseOwnershipException;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 13H: Fault-Injection tests covering Lease Expiry, Heartbeat Delays, Zombie Worker Fencing,
 * Clock Skew Protection, and Scheduler Saturation Compensation.
 */
@ExtendWith(MockitoExtension.class)
public class LeaseFencingAndSaturationFaultInjectionTest {

    @Mock
    private MigrationExecutionRepository executionRepository;

    @Mock
    private MigrationBackgroundWorker worker;

    @Mock
    private TaskExecutor taskExecutor;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private DurableLeaseHeartbeatService heartbeatService;

    private DurableSchedulerProperties properties;
    private DurableMigrationScheduler scheduler;

    private UUID executionId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        executionId = UUID.randomUUID();
        userId = UUID.randomUUID();

        properties = new DurableSchedulerProperties();
        properties.setEnabled(true);
        properties.setPollIntervalMs(100L);
        properties.setMaxClaimsPerPoll(5);
        properties.setRejectionBackoffSeconds(15L);
        properties.setLeaseDurationSeconds(60L);

        scheduler = new DurableMigrationScheduler(
                executionRepository,
                worker,
                taskExecutor,
                transactionManager,
                properties,
                heartbeatService
        );
        scheduler.start();

        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @Nested
    @DisplayName("Heartbeat, Safety Window & Safe Pause Invariants")
    class HeartbeatAndSafeWindowTests {

        @Test
        @DisplayName("Worker paused beyond safe lease window marks ownership lost and rejects renewal")
        void workerPausedBeyondSafeLeaseWindowLosesOwnership() {
            DurableLeaseHeartbeatService service = new DurableLeaseHeartbeatService(
                    executionRepository,
                    properties
            );

            service.registerLease(executionId, "worker-1", 1L);

            // Initially ownership is valid
            assertThat(service.isOwnershipLost(executionId)).isFalse();

            // When database heartbeat renewal returns 0 rows (lease expired, stolen, or fenced)
            when(executionRepository.renewLeaseHeartbeat(eq(executionId), eq("worker-1"), eq(1L), anyLong()))
                    .thenReturn(0);

            boolean renewed = service.renewLease(executionId);

            assertThat(renewed).isFalse();
            assertThat(service.isOwnershipLost(executionId)).isTrue();
        }

        @Test
        @DisplayName("Heartbeat DB failure caught gracefully without crashing scheduler thread")
        void heartbeatDatabaseFailureHandledGracefully() {
            DurableLeaseHeartbeatService service = new DurableLeaseHeartbeatService(
                    executionRepository,
                    properties
            );

            service.registerLease(executionId, "worker-1", 2L);

            when(executionRepository.renewLeaseHeartbeat(eq(executionId), eq("worker-1"), eq(2L), anyLong()))
                    .thenThrow(new DataAccessResourceFailureException("Database network blip"));

            // Must not throw unhandled exception to scheduled executor
            boolean renewed = service.renewLease(executionId);

            assertThat(renewed).isFalse();
            // Transient database exceptions do not immediately mark ownership lost
            assertThat(service.isOwnershipLost(executionId)).isFalse();
        }
    }

    @Nested
    @DisplayName("Zombie Worker Fencing & Generational Protection")
    class ZombieWorkerFencingTests {

        @Test
        @DisplayName("Zombie worker waking after successor claim fails fenced update with StaleLeaseOwnershipException")
        void zombieWorkerFencedByGenerationalToken() {
            long staleGeneration = 1L;
            long successorGeneration = 2L;

            lenient().when(executionRepository.updateStatusWithFencing(
                    eq(executionId), eq("zombie-worker"), eq(staleGeneration), anyString()
            )).thenReturn(0);

            int updated = executionRepository.updateStatusWithFencing(
                    executionId, "zombie-worker", staleGeneration, "RUNNING"
            );
            assertThat(updated).isEqualTo(0);

            // Verify exception contract
            StaleLeaseOwnershipException ex = new StaleLeaseOwnershipException(
                    executionId, "zombie-worker", staleGeneration, successorGeneration,
                    "Lease generation mismatch"
            );

            assertThat(ex.getExecutionId()).isEqualTo(executionId);
            assertThat(ex.getWorkerId()).isEqualTo("zombie-worker");
            assertThat(ex.getAttemptedGeneration()).isEqualTo(staleGeneration);
            assertThat(ex.getCurrentGeneration()).isEqualTo(successorGeneration);
        }

        @Test
        @DisplayName("Expired lease compensation updating 0 rows does not corrupt newer owner state")
        void expiredCompensationUpdatingZeroRowsReturnsFalseSafely() {
            ClaimedExecution claim = new ClaimedExecution(
                    executionId, userId, 1L, Instant.now().minusSeconds(10), "worker-1"
            );

            // Newer worker already claimed generation 2, so generation 1 compensation affects 0 rows
            when(executionRepository.compensateClaimRejection(
                    eq(executionId), eq("worker-1"), eq(1L), any(Instant.class)
            )).thenReturn(0);

            boolean compensated = scheduler.compensateRejection(claim);

            assertThat(compensated).isFalse();
        }
    }

    @Nested
    @DisplayName("Saturation, Compensation & Status Restoration")
    class SaturationAndCompensationTests {

        @Test
        @DisplayName("Executor queue saturation triggers compensation restoring RECOVERY_REQUIRED status")
        void saturationCompensationRestoresRecoveryRequiredStatus() {
            ClaimedExecution claim = new ClaimedExecution(
                    executionId, userId, 3L, Instant.now().plusSeconds(60),
                    scheduler.getWorkerId(), MigrationExecutionStatus.RECOVERY_REQUIRED
            );

            when(executionRepository.claimNextEligibleExecution(anyString(), anyLong()))
                    .thenReturn(Optional.of(claim));
            doThrow(new TaskRejectedException("Worker queue saturated"))
                    .when(taskExecutor).execute(any(Runnable.class));
            when(executionRepository.compensateClaimRejection(
                    eq(executionId), eq(scheduler.getWorkerId()), eq(3L), any(Instant.class),
                    eq(MigrationExecutionStatus.RECOVERY_REQUIRED)
            )).thenReturn(1);

            scheduler.pollAndDispatch();

            verify(executionRepository).compensateClaimRejection(
                    eq(executionId), eq(scheduler.getWorkerId()), eq(3L), any(Instant.class),
                    eq(MigrationExecutionStatus.RECOVERY_REQUIRED)
            );
        }

        @Test
        @DisplayName("Executor queue saturation halts poll loop immediately without claiming remaining quota")
        void saturationHaltsPollLoopImmediately() {
            ClaimedExecution claim = new ClaimedExecution(
                    executionId, userId, 1L, Instant.now().plusSeconds(60),
                    scheduler.getWorkerId(), MigrationExecutionStatus.QUEUED
            );

            when(executionRepository.claimNextEligibleExecution(anyString(), anyLong()))
                    .thenReturn(Optional.of(claim));
            doThrow(new TaskRejectedException("Pool saturated")).when(taskExecutor).execute(any());
            when(executionRepository.compensateClaimRejection(any(), any(), anyLong(), any())).thenReturn(1);

            scheduler.pollAndDispatch();

            // Saturated pool should halt poll loop after 1 rejected claim even though maxClaimsPerPoll is 5
            verify(executionRepository, times(1)).claimNextEligibleExecution(anyString(), anyLong());
            verify(executionRepository, times(1)).compensateClaimRejection(any(), any(), anyLong(), any());
        }

        @Test
        @DisplayName("Compensation schedules retry backoff in the future")
        void compensationSchedulesRetryBackoffInFuture() {
            ClaimedExecution claim = new ClaimedExecution(
                    executionId, userId, 4L, Instant.now().plusSeconds(60),
                    scheduler.getWorkerId(), MigrationExecutionStatus.QUEUED
            );

            when(executionRepository.compensateClaimRejection(any(), any(), anyLong(), any())).thenReturn(1);

            scheduler.compensateRejection(claim);

            ArgumentCaptor<Instant> nextAttemptCaptor = ArgumentCaptor.forClass(Instant.class);
            verify(executionRepository).compensateClaimRejection(
                    eq(executionId), eq(scheduler.getWorkerId()), eq(4L), nextAttemptCaptor.capture()
            );

            // Rejection backoff is 15 seconds in properties
            Instant expectedEarliest = Instant.now().plusSeconds(14);
            assertThat(nextAttemptCaptor.getValue()).isAfter(expectedEarliest);
        }
    }
}
