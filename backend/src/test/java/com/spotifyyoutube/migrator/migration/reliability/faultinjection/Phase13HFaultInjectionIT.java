package com.spotifyyoutube.migrator.migration.reliability.faultinjection;

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
import com.spotifyyoutube.migrator.migration.reliability.idempotency.MigrationIdempotencyRepository;
import com.spotifyyoutube.migrator.migration.reliability.scheduling.ClaimedExecution;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 13H: PostgreSQL Testcontainers Integration Test Suite for Fault Injection & Concurrency Verification.
 * Validates real database concurrency (SKIP LOCKED), atomic generation fencing, wall-clock time expiration,
 * rollback intent durability across simulated worker crashes, and idempotency key uniqueness.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JpaConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Phase 13H: PostgreSQL Fault-Injection & Concurrency Integration Tests")
public class Phase13HFaultInjectionIT {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReviewSessionRepository reviewSessionRepository;

    @Autowired
    private MigrationExecutionRepository executionRepository;

    @Autowired
    private ExecutionTrackRecordRepository trackRecordRepository;

    @Autowired
    private MigrationIdempotencyRepository idempotencyRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private User testUser;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        testUser = userRepository.save(new User("phase13h-fault-it@example.com"));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    private void cleanDatabase() {
        if (entityManager != null) {
            entityManager.clear();
        }
        idempotencyRepository.deleteAllInBatch();
        trackRecordRepository.deleteAllInBatch();
        executionRepository.deleteAllInBatch();
        reviewSessionRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private MigrationExecution createExecution(MigrationExecutionStatus status) {
        ReviewSession session = reviewSessionRepository.save(new ReviewSession(
                testUser,
                "sp_pl_" + UUID.randomUUID(),
                "Title " + UUID.randomUUID(),
                UUID.randomUUID()
        ));

        ApprovedTrack track1 = new ApprovedTrack(
                0, "sp_t1", "Track 1", "Artist 1",
                "yt_v1", "Video 1", "Channel 1",
                Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 0
        );
        ApprovedTrack track2 = new ApprovedTrack(
                1, "sp_t2", "Track 2", "Artist 2",
                "yt_v2", "Video 2", "Channel 2",
                Duration.ofMinutes(4), ReviewDecisionSource.AUTOMATIC, 1
        );

        ApprovedMigrationContext context = new ApprovedMigrationContext(
                session.getId(), UUID.randomUUID(), testUser.getId(),
                "sp_src_" + UUID.randomUUID(), "Source Title", "Target Playlist",
                "Description", false, List.of(track1, track2),
                2, 2, 0, Instant.now()
        );

        MigrationExecution exec = new MigrationExecution(
                testUser, session, context,
                "PL_" + UUID.randomUUID(), "https://music.youtube.com/playlist?list=PL_test",
                MigrationExecutionStatus.QUEUED
        );

        if (status != null && status != MigrationExecutionStatus.QUEUED) {
            exec.setStatus(status);
        }

        MigrationExecution saved = executionRepository.saveAndFlush(exec);
        if (entityManager != null) {
            entityManager.clear();
        }
        return saved;
    }

    @Test
    @DisplayName("Concurrent workers race to claim single execution: exactly one wins, zero duplicate claims")
    void concurrentWorkersClaimSingleExecutionAtomically() throws Exception {
        MigrationExecution execution = createExecution(MigrationExecutionStatus.QUEUED);
        UUID executionId = execution.getId();

        int workerCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Optional<ClaimedExecution>>> futures = new ArrayList<>();

        for (int i = 0; i < workerCount; i++) {
            final String workerId = "worker-thread-" + i;
            futures.add(executor.submit(() -> {
                startLatch.await();
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                return tx.execute(status -> executionRepository.claimNextEligibleExecution(workerId, 60L));
            }));
        }

        // Release all workers simultaneously
        startLatch.countDown();

        int successfulClaims = 0;
        ClaimedExecution winningClaim = null;
        for (Future<Optional<ClaimedExecution>> future : futures) {
            Optional<ClaimedExecution> claim = future.get(10, TimeUnit.SECONDS);
            if (claim.isPresent() && claim.get().executionId().equals(executionId)) {
                successfulClaims++;
                winningClaim = claim.get();
            }
        }

        executor.shutdown();

        // Invariant: Exactly one claim succeeds across concurrent workers
        assertThat(successfulClaims).isEqualTo(1);
        assertThat(winningClaim).isNotNull();
        assertThat(winningClaim.leaseGeneration()).isEqualTo(1L);

        // Verify database state: status is RUNNING, lease generation is 1
        MigrationExecution updated = executionRepository.findById(executionId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
        assertThat(updated.getLeaseGeneration()).isEqualTo(1L);
        assertThat(updated.getLeaseOwner()).isEqualTo(winningClaim.workerId());
    }

    @Test
    @DisplayName("Zombie worker fenced out after lease expiration: heartbeat renewal returns 0 rows updated")
    void zombieWorkerFencedAfterLeaseExpiration() {
        MigrationExecution execution = createExecution(MigrationExecutionStatus.QUEUED);
        UUID executionId = execution.getId();

        // Claim execution under worker 1 with a 1-second lease
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        ClaimedExecution claim = tx.execute(status ->
                executionRepository.claimNextEligibleExecution("worker-1", 1L)
        ).orElseThrow();

        assertThat(claim.leaseGeneration()).isEqualTo(1L);

        // Intentionally expire the lease by setting lease_until in the past
        tx.execute(status -> {
            MigrationExecution exec = executionRepository.findById(executionId).orElseThrow();
            exec.setLeaseUntil(Instant.now().minusSeconds(10));
            executionRepository.saveAndFlush(exec);
            return null;
        });

        // Worker 1 wakes up after pause and attempts to renew lease
        int renewed = tx.execute(status ->
                executionRepository.renewLeaseHeartbeat(executionId, "worker-1", 1L, 60L)
        );

        // Invariant: PostgreSQL clock_timestamp() expiration check fences out the expired worker
        assertThat(renewed).isEqualTo(0);
    }

    @Test
    @DisplayName("Zombie worker fenced out after successor increments generation: fenced update returns 0 rows")
    void zombieWorkerFencedAfterSuccessorIncrementsGeneration() {
        MigrationExecution execution = createExecution(MigrationExecutionStatus.QUEUED);
        UUID executionId = execution.getId();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Worker 1 claims generation 1
        ClaimedExecution claim1 = tx.execute(status ->
                executionRepository.claimNextEligibleExecution("worker-1", 1L)
        ).orElseThrow();

        // Expire lease
        tx.execute(status -> {
            MigrationExecution exec = executionRepository.findById(executionId).orElseThrow();
            exec.setLeaseUntil(Instant.now().minusSeconds(10));
            executionRepository.saveAndFlush(exec);
            return null;
        });

        // Worker 2 takes over and increments generation to 2
        ClaimedExecution claim2 = tx.execute(status ->
                executionRepository.claimNextEligibleExecution("worker-2", 60L)
        ).orElseThrow();

        assertThat(claim2.leaseGeneration()).isEqualTo(2L);
        assertThat(claim2.workerId()).isEqualTo("worker-2");

        // Zombie Worker 1 attempts to update execution status using stale generation 1
        int updated = tx.execute(status ->
                executionRepository.updateStatusWithFencing(executionId, "worker-1", 1L, "COMPLETED")
        );

        // Invariant: Stale generational token guarantees 0 rows modified
        assertThat(updated).isEqualTo(0);

        // Confirm database still owned by Worker 2 with generation 2
        MigrationExecution current = executionRepository.findById(executionId).orElseThrow();
        assertThat(current.getLeaseGeneration()).isEqualTo(2L);
        assertThat(current.getLeaseOwner()).isEqualTo("worker-2");
        assertThat(current.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
    }

    @Test
    @DisplayName("Rollback intent durability: crash after intent preserves rollback request for scheduler recovery")
    void rollbackIntentPreservedAcrossSimulatedCrash() {
        MigrationExecution execution = createExecution(MigrationExecutionStatus.RUNNING);
        UUID executionId = execution.getId();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        long currentVersion = execution.getVersion();
        // Claim execution for rollback with intent (V14 migration columns)
        int claimed = tx.execute(status ->
                executionRepository.claimExecutionForRollbackWithIntent(
                        executionId,
                        List.of("RUNNING", "COMPLETED", "FAILED"),
                        currentVersion,
                        "rollback-worker",
                        "0,1",
                        60L
                )
        );
        assertThat(claimed).isEqualTo(1);

        // Verify rollback intent columns are committed
        MigrationExecution withIntent = executionRepository.findById(executionId).orElseThrow();
        assertThat(withIntent.getRollbackRequestedAt()).isNotNull();
        assertThat(withIntent.getRollbackTargetPositions()).isEqualTo("0,1");

        // Simulate crash: worker dies, lease expires
        tx.execute(status -> {
            MigrationExecution exec = executionRepository.findById(executionId).orElseThrow();
            exec.setLeaseUntil(Instant.now().minusSeconds(10));
            executionRepository.saveAndFlush(exec);
            return null;
        });

        // Recovery worker claims execution via scheduler
        ClaimedExecution recoveryClaim = tx.execute(status ->
                executionRepository.claimNextEligibleExecution("recovery-worker", 60L)
        ).orElseThrow();

        assertThat(recoveryClaim.executionId()).isEqualTo(executionId);
        long successorGeneration = recoveryClaim.leaseGeneration();

        // Verify execution retained its rollback intent
        MigrationExecution recovered = executionRepository.findById(executionId).orElseThrow();
        assertThat(recovered.getRollbackRequestedAt()).isNotNull();
        assertThat(recovered.getRollbackTargetPositions()).isEqualTo("0,1");

        // Successor finalizes rollback with generation
        int finalized = tx.execute(status ->
                executionRepository.finalizeRollbackWithFencing(
                        executionId,
                        MigrationExecutionStatus.ROLLED_BACK.name(),
                        "recovery-worker",
                        successorGeneration
                )
        );
        assertThat(finalized).isEqualTo(1);

        MigrationExecution terminal = executionRepository.findById(executionId).orElseThrow();
        assertThat(terminal.getStatus()).isEqualTo(MigrationExecutionStatus.ROLLED_BACK);
        assertThat(terminal.getLeaseOwner()).isNull();
    }

    @Test
    @DisplayName("Concurrent idempotency claims: database unique constraint prevents duplicate pending claims")
    void concurrentIdempotencyClaimsPreventDuplicates() throws Exception {
        UUID reviewSessionId = UUID.randomUUID();
        String idempotencyKey = "concurrent-key-" + UUID.randomUUID();
        String fingerprint = "sha256-payload-" + UUID.randomUUID();

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String workerOwner = "idem-worker-" + i;
            futures.add(executor.submit(() -> {
                startLatch.await();
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                try {
                    return tx.execute(status -> idempotencyRepository.insertPendingClaimDatabaseTime(
                            UUID.randomUUID(), testUser.getId(), idempotencyKey, fingerprint,
                            reviewSessionId, workerOwner, 60L
                    ));
                } catch (DataIntegrityViolationException dive) {
                    return 0; // Collided on unique constraint
                }
            }));
        }

        startLatch.countDown();

        int successfulInserts = 0;
        for (Future<Integer> f : futures) {
            successfulInserts += f.get(10, TimeUnit.SECONDS);
        }

        executor.shutdown();

        // Invariant: Exactly one thread succeeded in inserting the pending claim
        assertThat(successfulInserts).isEqualTo(1);
    }
}
