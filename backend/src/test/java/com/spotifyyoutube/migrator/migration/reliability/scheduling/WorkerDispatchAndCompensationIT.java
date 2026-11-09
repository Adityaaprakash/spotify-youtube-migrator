package com.spotifyyoutube.migrator.migration.reliability.scheduling;

import com.spotifyyoutube.migrator.common.config.JpaConfig;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.async.MigrationBackgroundWorker;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * PostgreSQL Testcontainers integration test suite verifying Phase 13D-C post-commit worker dispatch,
 * transaction boundary separation, executor rejection compensation, and stale compensation protection.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JpaConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Phase 13D-C: PostgreSQL Worker Dispatch & Saturation Compensation Integration Tests")
public class WorkerDispatchAndCompensationIT {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReviewSessionRepository reviewSessionRepository;

    @Autowired
    private MigrationExecutionRepository executionRepository;

    @Autowired
    private ExecutionTrackRecordRepository trackRecordRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private User testUser;
    private MigrationBackgroundWorker mockWorker;
    private TaskExecutor mockTaskExecutor;
    private DurableSchedulerProperties properties;
    private DurableMigrationScheduler scheduler;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        testUser = userRepository.save(new User("dispatch-it@example.com"));

        mockWorker = mock(MigrationBackgroundWorker.class);
        mockTaskExecutor = mock(TaskExecutor.class);

        properties = new DurableSchedulerProperties();
        properties.setEnabled(true);
        properties.setLeaseDurationSeconds(60L);
        properties.setRejectionBackoffSeconds(15L);
        properties.setMaxClaimsPerPoll(4);
        properties.setWorkerIdPrefix("it-scheduler-");

        scheduler = new DurableMigrationScheduler(
                executionRepository,
                mockWorker,
                mockTaskExecutor,
                transactionManager,
                properties
        );
        scheduler.start();
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.stop();
        }
        cleanDatabase();
    }

    private void cleanDatabase() {
        if (entityManager != null) {
            entityManager.clear();
        }
        trackRecordRepository.deleteAllInBatch();
        executionRepository.deleteAllInBatch();
        reviewSessionRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private MigrationExecution createQueuedExecution() {
        ReviewSession session = reviewSessionRepository.save(new ReviewSession(
                testUser,
                "sp_pl_" + UUID.randomUUID(),
                "Dispatch IT Playlist",
                UUID.randomUUID()
        ));

        ApprovedTrack track0 = new ApprovedTrack(
                0, "sp_t0", "Track Zero", "Artist",
                "yt_t0", "Video Zero", "Channel",
                Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 0
        );

        ApprovedMigrationContext context = new ApprovedMigrationContext(
                session.getId(), UUID.randomUUID(), testUser.getId(),
                "sp_src_" + UUID.randomUUID(), "Source", "Target Playlist",
                "Description", false, List.of(track0),
                1, 1, 0, Instant.now()
        );

        MigrationExecution exec = new MigrationExecution(
                testUser, session, context,
                "PL_" + UUID.randomUUID(), "https://music.youtube.com/playlist?list=PL_it",
                MigrationExecutionStatus.QUEUED
        );

        MigrationExecution saved = executionRepository.saveAndFlush(exec);
        if (entityManager != null) {
            entityManager.clear();
        }
        return saved;
    }

    @Test
    @DisplayName("Dispatch success: worker receives exact fencing generation, user ID, and execution ID")
    void dispatchSuccessPropagatesFencingToken() {
        MigrationExecution execution = createQueuedExecution();

        AtomicBoolean workerExecuted = new AtomicBoolean(false);
        AtomicReference<UUID> receivedExecId = new AtomicReference<>();
        AtomicReference<UUID> receivedUserId = new AtomicReference<>();
        AtomicReference<Long> receivedGen = new AtomicReference<>();

        doAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            r.run();
            return null;
        }).when(mockTaskExecutor).execute(any(Runnable.class));

        doAnswer(invocation -> {
            receivedExecId.set(invocation.getArgument(0));
            receivedUserId.set(invocation.getArgument(1));
            receivedGen.set(invocation.getArgument(2));
            workerExecuted.set(true);
            return null;
        }).when(mockWorker).processExecution(any(), any(), any(Long.class));

        Optional<ClaimedExecution> claimOpt = scheduler.claimNextExecution();
        assertThat(claimOpt).isPresent();
        ClaimedExecution claim = claimOpt.get();

        boolean dispatched = scheduler.dispatchClaimedExecution(claim);
        assertThat(dispatched).isTrue();
        assertThat(workerExecuted.get()).isTrue();
        assertThat(receivedExecId.get()).isEqualTo(execution.getId());
        assertThat(receivedUserId.get()).isEqualTo(testUser.getId());
        assertThat(receivedGen.get()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Transaction separation: claim transaction is committed before worker executes, worker runs without active DB transaction")
    void transactionSeparationVerified() throws Exception {
        MigrationExecution execution = createQueuedExecution();

        CountDownLatch workerStartedLatch = new CountDownLatch(1);
        AtomicBoolean hasActiveTxInWorker = new AtomicBoolean(true);
        AtomicReference<MigrationExecutionStatus> statusInDbBeforeWorkerFinishes = new AtomicReference<>();
        AtomicReference<String> ownerInDbBeforeWorkerFinishes = new AtomicReference<>();

        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            Thread workerThread = new Thread(() -> {
                // Check if worker thread has an inherited open transaction
                hasActiveTxInWorker.set(TransactionSynchronizationManager.isActualTransactionActive());

                // Read committed state from PostgreSQL in worker thread
                MigrationExecution exec = executionRepository.findById(execution.getId()).orElse(null);
                if (exec != null) {
                    statusInDbBeforeWorkerFinishes.set(exec.getStatus());
                    ownerInDbBeforeWorkerFinishes.set(exec.getLeaseOwner());
                }

                task.run();
                workerStartedLatch.countDown();
            });
            workerThread.start();
            return null;
        }).when(mockTaskExecutor).execute(any(Runnable.class));

        Optional<ClaimedExecution> claimOpt = scheduler.claimNextExecution();
        assertThat(claimOpt).isPresent();

        scheduler.dispatchClaimedExecution(claimOpt.get());

        assertThat(workerStartedLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(hasActiveTxInWorker.get()).as("Worker thread must NOT inherit an active database transaction").isFalse();
        assertThat(statusInDbBeforeWorkerFinishes.get()).as("Claim transaction must be committed before worker executes")
                .isEqualTo(MigrationExecutionStatus.RUNNING);
        assertThat(ownerInDbBeforeWorkerFinishes.get()).isEqualTo(scheduler.getWorkerId());
    }

    @Test
    @DisplayName("Executor rejection compensation: TaskRejectedException resets execution to QUEUED with backoff")
    void executorRejectionCompensation() {
        MigrationExecution execution = createQueuedExecution();

        doThrow(new TaskRejectedException("Worker executor queue saturated"))
                .when(mockTaskExecutor).execute(any(Runnable.class));

        Optional<ClaimedExecution> claimOpt = scheduler.claimNextExecution();
        assertThat(claimOpt).isPresent();
        ClaimedExecution claim = claimOpt.get();

        boolean dispatched = scheduler.dispatchClaimedExecution(claim);
        assertThat(dispatched).isFalse();

        // Verify compensation in PostgreSQL
        MigrationExecution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).as("Execution must be reset to QUEUED upon executor rejection")
                .isEqualTo(MigrationExecutionStatus.QUEUED);
        assertThat(reloaded.getLeaseOwner()).as("Lease owner must be cleared upon compensation").isNull();
        assertThat(reloaded.getLeaseUntil()).as("Lease expiry must be cleared upon compensation").isNull();
        assertThat(reloaded.getNextAttemptAt())
                .as("Retry backoff timestamp must be set in the future")
                .isAfter(Instant.now());
        assertThat(reloaded.getLeaseGeneration()).isEqualTo(1L);

        // Verify track states and counts are preserved
        List<ExecutionTrackRecord> tracks = trackRecordRepository.findByExecutionId(execution.getId());
        assertThat(tracks).hasSize(1);
        assertThat(tracks.get(0).getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);
        assertThat(reloaded.getSuccessfulInsertions()).isEqualTo(0);
        assertThat(reloaded.getFailedInsertions()).isEqualTo(0);
    }

    @Test
    @DisplayName("Stale compensation protection: older generation does not mutate newer worker lease")
    void staleCompensationProtection() {
        MigrationExecution execution = createQueuedExecution();

        // Worker 1 claims execution with generation 1
        Optional<ClaimedExecution> claimGen1 = scheduler.claimNextExecution();
        assertThat(claimGen1).isPresent();
        assertThat(claimGen1.get().leaseGeneration()).isEqualTo(1L);

        // Simulate lease expiration so Worker 2 can take over
        org.springframework.transaction.support.TransactionTemplate txTemplate =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery(
                    "UPDATE migration_executions SET lease_until = clock_timestamp() - INTERVAL '10 seconds' WHERE id = :id"
            ).setParameter("id", execution.getId()).executeUpdate();
        });

        // Worker 2 takes over with generation 2
        String worker2Id = "worker-2-takeover";
        Optional<ClaimedExecution> worker2Claim = executionRepository.claimNextEligibleExecution(worker2Id, 120L);
        assertThat(worker2Claim).isPresent();
        assertThat(worker2Claim.get().leaseGeneration()).isEqualTo(2L);

        // Verify Worker 2 now owns generation 2
        MigrationExecution takeover = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(takeover.getLeaseOwner()).isEqualTo(worker2Id);
        assertThat(takeover.getLeaseGeneration()).isEqualTo(2L);

        // Worker 1 now attempts compensation using its stale claim (generation 1)
        boolean compensated = scheduler.compensateRejection(claimGen1.get());
        assertThat(compensated).as("Compensation with stale generation must return false").isFalse();

        // Verify generation 2 lease was NOT released and worker 2 is still the owner
        MigrationExecution intact = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(intact.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
        assertThat(intact.getLeaseOwner()).isEqualTo(worker2Id);
        assertThat(intact.getLeaseGeneration()).isEqualTo(2L);
        assertThat(intact.getLeaseUntil()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("Compensation rejects non-RUNNING status: non-RUNNING status is protected from QUEUED reset")
    void compensationRejectsNonRunningStatus() {
        MigrationExecution execution = createQueuedExecution();

        Optional<ClaimedExecution> claimOpt = scheduler.claimNextExecution();
        assertThat(claimOpt).isPresent();
        ClaimedExecution claim = claimOpt.get();

        // Simulate external transition to COMPLETED before compensation runs
        org.springframework.transaction.support.TransactionTemplate txTemplate =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery(
                    "UPDATE migration_executions SET status = 'COMPLETED' WHERE id = :id"
            ).setParameter("id", execution.getId()).executeUpdate();
        });

        boolean compensated = scheduler.compensateRejection(claim);
        assertThat(compensated).as("Compensation on non-RUNNING status must return false").isFalse();

        MigrationExecution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MigrationExecutionStatus.COMPLETED);
    }

    @Test
    @DisplayName("Compensation rejects expired lease: wall-clock expired lease cannot be compensated")
    void compensationRejectsExpiredLease() {
        MigrationExecution execution = createQueuedExecution();

        Optional<ClaimedExecution> claimOpt = scheduler.claimNextExecution();
        assertThat(claimOpt).isPresent();
        ClaimedExecution claim = claimOpt.get();

        // Expire lease in PostgreSQL
        org.springframework.transaction.support.TransactionTemplate txTemplate =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery(
                    "UPDATE migration_executions SET lease_until = clock_timestamp() - INTERVAL '5 seconds' WHERE id = :id"
            ).setParameter("id", execution.getId()).executeUpdate();
        });

        boolean compensated = scheduler.compensateRejection(claim);
        assertThat(compensated).as("Compensation on expired lease must return false").isFalse();

        MigrationExecution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
    }

    @Test
    @DisplayName("Graceful shutdown: stopped scheduler refuses to claim or dispatch new executions")
    void gracefulShutdownProtection() {
        createQueuedExecution();

        scheduler.stop();
        assertThat(scheduler.isRunning()).isFalse();

        Optional<ClaimedExecution> claim = scheduler.claimNextExecution();
        assertThat(claim).isEmpty();

        scheduler.pollAndDispatch();
        verify(mockTaskExecutor, org.mockito.Mockito.never()).execute(any());
    }
}
