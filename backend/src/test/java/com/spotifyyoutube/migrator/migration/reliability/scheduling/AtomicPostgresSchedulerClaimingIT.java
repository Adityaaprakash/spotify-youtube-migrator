package com.spotifyyoutube.migrator.migration.reliability.scheduling;

import com.spotifyyoutube.migrator.common.config.JpaConfig;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL Testcontainers integration tests verifying Phase 13D-B atomic scheduler claiming.
 * Validates row-level locking, FOR UPDATE SKIP LOCKED, fencing generation increments,
 * active lease protection, expired lease recovery, and retry eligibility.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JpaConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Phase 13D-B: PostgreSQL Atomic Scheduler Claiming Integration Tests")
public class AtomicPostgresSchedulerClaimingIT {

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

    @BeforeEach
    void setUp() {
        cleanDatabase();
        testUser = userRepository.save(new User("scheduler-claim-it@example.com"));
    }

    @AfterEach
    void tearDown() {
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

    private MigrationExecution createExecution(
            MigrationExecutionStatus status,
            Instant leaseUntil,
            String leaseOwner,
            Long leaseGen,
            Instant nextAttemptAt
    ) {
        ReviewSession session = reviewSessionRepository.save(new ReviewSession(
                testUser,
                "sp_pl_" + UUID.randomUUID(),
                "Title " + UUID.randomUUID(),
                UUID.randomUUID()
        ));

        ApprovedTrack track = new ApprovedTrack(
                0, "sp_" + UUID.randomUUID(), "Song", "Artist",
                "yt_" + UUID.randomUUID(), "Video", "Channel",
                Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 0
        );

        ApprovedMigrationContext context = new ApprovedMigrationContext(
                session.getId(), UUID.randomUUID(), testUser.getId(),
                "sp_src_" + UUID.randomUUID(), "Source Title", "Target Playlist",
                "Description", false, List.of(track),
                1, 1, 0, Instant.now()
        );

        MigrationExecution exec = new MigrationExecution(
                testUser, session, context,
                "PL_" + UUID.randomUUID(), "https://music.youtube.com/playlist?list=PL_" + UUID.randomUUID(),
                MigrationExecutionStatus.QUEUED
        );

        if (status != null && status != MigrationExecutionStatus.QUEUED) {
            exec.setStatus(status);
        }

        if (leaseOwner != null) {
            exec.setLeaseOwner(leaseOwner);
        }
        if (leaseUntil != null) {
            exec.setLeaseUntil(leaseUntil);
        }
        if (leaseGen != null) {
            exec.setLeaseGeneration(leaseGen);
        }
        if (nextAttemptAt != null) {
            exec.setNextAttemptAt(nextAttemptAt);
        }

        MigrationExecution saved = executionRepository.saveAndFlush(exec);
        if (entityManager != null) {
            entityManager.clear();
        }
        return saved;
    }

    @Test
    @DisplayName("Test A: Two concurrent workers, one execution — exactly one claims, other receives empty")
    void twoConcurrentWorkersOneExecution() throws Exception {
        MigrationExecution execution = createExecution(MigrationExecutionStatus.QUEUED, null, null, 0L, null);

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        CompletableFuture<Optional<ClaimedExecution>> worker1 = CompletableFuture.supplyAsync(() -> {
            readyLatch.countDown();
            try {
                assertThat(startLatch.await(5, TimeUnit.SECONDS)).isTrue();
                return executionRepository.claimNextEligibleExecution("worker-1", 60L);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        CompletableFuture<Optional<ClaimedExecution>> worker2 = CompletableFuture.supplyAsync(() -> {
            readyLatch.countDown();
            try {
                assertThat(startLatch.await(5, TimeUnit.SECONDS)).isTrue();
                return executionRepository.claimNextEligibleExecution("worker-2", 60L);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
        startLatch.countDown();

        Optional<ClaimedExecution> result1 = worker1.get(5, TimeUnit.SECONDS);
        Optional<ClaimedExecution> result2 = worker2.get(5, TimeUnit.SECONDS);

        // Exactly one claimed, the other got empty
        boolean worker1Won = result1.isPresent();
        boolean worker2Won = result2.isPresent();
        assertThat(worker1Won ^ worker2Won).as("Exactly one worker must claim the single execution").isTrue();

        ClaimedExecution winningClaim = worker1Won ? result1.get() : result2.get();
        String winningWorkerId = worker1Won ? "worker-1" : "worker-2";

        assertThat(winningClaim.executionId()).isEqualTo(execution.getId());
        assertThat(winningClaim.leaseGeneration()).isEqualTo(1L);
        assertThat(winningClaim.workerId()).isEqualTo(winningWorkerId);
        assertThat(winningClaim.leaseUntil()).isAfter(Instant.now());

        // Verify database state reflects winning worker and incremented generation
        MigrationExecution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
        assertThat(reloaded.getLeaseOwner()).isEqualTo(winningWorkerId);
        assertThat(reloaded.getLeaseGeneration()).isEqualTo(1L);
        assertThat(reloaded.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("Test B: Two concurrent workers, multiple executions — claimed at most once with distinct IDs")
    void twoConcurrentWorkersMultipleExecutions() throws Exception {
        int executionCount = 6;
        List<UUID> createdIds = new ArrayList<>();
        for (int i = 0; i < executionCount; i++) {
            createdIds.add(createExecution(MigrationExecutionStatus.QUEUED, null, null, 0L, null).getId());
        }

        Set<UUID> claimedIds = ConcurrentHashMap.newKeySet();
        CountDownLatch startLatch = new CountDownLatch(1);

        ThrowingConsumer<String> claimTask = (workerId) -> {
            try {
                assertThat(startLatch.await(5, TimeUnit.SECONDS)).isTrue();
                for (int i = 0; i < executionCount; i++) {
                    Optional<ClaimedExecution> claim = executionRepository.claimNextEligibleExecution(workerId, 60L);
                    claim.ifPresent(c -> {
                        boolean added = claimedIds.add(c.executionId());
                        assertThat(added).as("Duplicate claim detected for execution: " + c.executionId()).isTrue();
                    });
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        };

        CompletableFuture<Void> worker1 = CompletableFuture.runAsync(
                () -> {
                    try {
                        claimTask.accept("worker-1");
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });

        CompletableFuture<Void> worker2 = CompletableFuture.runAsync(
                () -> {
                    try {
                        claimTask.accept("worker-2");
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });

        startLatch.countDown();
        worker1.get(10, TimeUnit.SECONDS);
        worker2.get(10, TimeUnit.SECONDS);

        assertThat(claimedIds).hasSize(executionCount);
        assertThat(claimedIds).containsExactlyInAnyOrderElementsOf(createdIds);
    }

    @Test
    @DisplayName("Test C: Active lease protection — worker B cannot acquire execution owned by worker A")
    void activeLeaseProtection() {
        Instant futureLease = Instant.now().plusSeconds(60);
        MigrationExecution execution = createExecution(
                MigrationExecutionStatus.RUNNING,
                futureLease,
                "worker-A",
                1L,
                null
        );

        Optional<ClaimedExecution> claimOpt = executionRepository.claimNextEligibleExecution("worker-B", 60L);

        assertThat(claimOpt).isEmpty();

        MigrationExecution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getLeaseOwner()).isEqualTo("worker-A");
        assertThat(reloaded.getLeaseGeneration()).isEqualTo(1L);
        assertThat(reloaded.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
    }

    @Test
    @DisplayName("Test D: Expired lease recovery — new worker acquires execution and invalidates old generation")
    void expiredLeaseRecovery() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        Instant expiredLease = Instant.now().minusSeconds(15);
        MigrationExecution execution = createExecution(
                MigrationExecutionStatus.RUNNING,
                expiredLease,
                "worker-A",
                3L,
                null
        );

        // Populate track in PENDING state to test old fencing token invalidation
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            ExecutionTrackRecord track = exec.getTrackRecords().get(0);
            trackRecordRepository.saveAndFlush(track);
        });
        UUID trackId = execution.getTrackRecords().get(0).getId();

        // Worker B claims the expired execution
        Optional<ClaimedExecution> claimOpt = executionRepository.claimNextEligibleExecution("worker-B", 60L);

        assertThat(claimOpt).isPresent();
        ClaimedExecution claim = claimOpt.get();
        assertThat(claim.executionId()).isEqualTo(execution.getId());
        assertThat(claim.workerId()).isEqualTo("worker-B");
        assertThat(claim.leaseGeneration()).isEqualTo(4L); // Monotonic increment from 3L

        // Verify old generation (3L) cannot mutate track state
        int oldWorkerMutated = trackRecordRepository.claimTrackAddingWithFencing(
                trackId,
                execution.getId(),
                3L
        );
        assertThat(oldWorkerMutated).as("Stale worker with generation 3L must be rejected by fencing").isEqualTo(0);

        // Verify new generation (4L) can mutate track state
        int newWorkerMutated = trackRecordRepository.claimTrackAddingWithFencing(
                trackId,
                execution.getId(),
                4L
        );
        assertThat(newWorkerMutated).as("New worker with generation 4L must succeed").isEqualTo(1);
    }

    @Test
    @DisplayName("Test E: Future retry protection — execution with next_attempt_at in future is not claimable")
    void futureRetryProtection() {
        Instant futureRetry = Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.MICROS);
        MigrationExecution execution = createExecution(
                MigrationExecutionStatus.QUEUED,
                null,
                null,
                0L,
                futureRetry
        );

        Optional<ClaimedExecution> claimOpt = executionRepository.claimNextEligibleExecution("worker-1", 60L);

        assertThat(claimOpt).isEmpty();

        MigrationExecution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MigrationExecutionStatus.QUEUED);
        assertThat(reloaded.getLeaseOwner()).isNull();
        assertThat(reloaded.getNextAttemptAt()).isEqualTo(futureRetry);
    }

    @Test
    @DisplayName("Test F: Due retry eligibility — execution with next_attempt_at in past is claimed and cleared")
    void dueRetryEligibility() {
        Instant pastRetry = Instant.now().minusSeconds(30);
        MigrationExecution execution = createExecution(
                MigrationExecutionStatus.QUEUED,
                null,
                null,
                2L,
                pastRetry
        );

        Optional<ClaimedExecution> claimOpt = executionRepository.claimNextEligibleExecution("worker-1", 60L);

        assertThat(claimOpt).isPresent();
        ClaimedExecution claim = claimOpt.get();
        assertThat(claim.executionId()).isEqualTo(execution.getId());
        assertThat(claim.leaseGeneration()).isEqualTo(3L);

        MigrationExecution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
        assertThat(reloaded.getLeaseOwner()).isEqualTo("worker-1");
        assertThat(reloaded.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("Test G: Terminal execution protection — COMPLETED, FAILED, and ROLLED_BACK are never claimed")
    void terminalExecutionProtection() {
        createExecution(MigrationExecutionStatus.COMPLETED, null, null, 1L, null);
        createExecution(MigrationExecutionStatus.FAILED, null, null, 1L, null);
        createExecution(MigrationExecutionStatus.ROLLED_BACK, null, null, 1L, null);

        Optional<ClaimedExecution> claimOpt = executionRepository.claimNextEligibleExecution("worker-1", 60L);

        assertThat(claimOpt).isEmpty();
    }

    @Test
    @DisplayName("Test H: Lock skipping — row lock causes SKIP LOCKED to claim another execution without blocking")
    void lockSkipping() throws Exception {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        MigrationExecution exec1 = createExecution(MigrationExecutionStatus.QUEUED, null, null, 0L, null);
        MigrationExecution exec2 = createExecution(MigrationExecutionStatus.QUEUED, null, null, 0L, null);

        CountDownLatch lockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch releaseLockLatch = new CountDownLatch(1);

        // Transaction A locks exec1 via SELECT FOR UPDATE
        CompletableFuture<Void> txA = CompletableFuture.runAsync(() -> {
            txTemplate.executeWithoutResult(status -> {
                entityManager.createNativeQuery(
                        "SELECT id FROM migration_executions WHERE id = :id FOR UPDATE"
                )
                .setParameter("id", exec1.getId())
                .getResultList();

                lockAcquiredLatch.countDown();

                try {
                    boolean proceed = releaseLockLatch.await(10, TimeUnit.SECONDS);
                    assertThat(proceed).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        });

        try {
            assertThat(lockAcquiredLatch.await(5, TimeUnit.SECONDS)).isTrue();

            // Transaction B attempts to claim — with FOR UPDATE SKIP LOCKED, it must NOT block on exec1
            // but immediately claim exec2
            long startTime = System.currentTimeMillis();
            Optional<ClaimedExecution> claimOpt = txTemplate.execute(status ->
                    executionRepository.claimNextEligibleExecution("worker-B", 60L)
            );
            long duration = System.currentTimeMillis() - startTime;

            assertThat(duration).as("Claim operation must skip locked row immediately without waiting").isLessThan(2000);
            assertThat(claimOpt).isPresent();
            assertThat(claimOpt.get().executionId()).as("Must have skipped locked exec1 and claimed exec2")
                    .isEqualTo(exec2.getId());

            // Prove exec1 is genuinely locked: non-skipping NOWAIT query on exec1 fails with lock error
            assertThatThrownBy(() -> {
                txTemplate.executeWithoutResult(status -> {
                    entityManager.createNativeQuery(
                            "SELECT id FROM migration_executions WHERE id = :id FOR UPDATE NOWAIT"
                    ).setParameter("id", exec1.getId()).getResultList();
                });
            }).as("exec1 must be actively locked and reject concurrent non-skipping lock acquisition");

            // Prove PostgreSQL lock manager records active row locks in pg_locks
            Number locksOnExec1 = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM pg_locks WHERE locktype = 'transactionid' OR locktype = 'relation'"
            ).getSingleResult();
            assertThat(locksOnExec1.longValue()).isGreaterThan(0L);

        } finally {
            releaseLockLatch.countDown();
            txA.get(5, TimeUnit.SECONDS);
        }
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }
}
