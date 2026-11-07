package com.spotifyyoutube.migrator.migration.integration;

import com.spotifyyoutube.migrator.common.config.JpaConfig;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL database integration test for atomic QUEUED -> RUNNING execution claiming (Phase 12G-C).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JpaConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Phase 12G-C: PostgreSQL Atomic QUEUED Execution Claiming Integration Test")
public class AtomicQueuedExecutionClaimIT {

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

    private User testUser;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        testUser = userRepository.save(new User("claim-it-" + UUID.randomUUID() + "@example.com"));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    private void cleanDatabase() {
        trackRecordRepository.deleteAll();
        executionRepository.deleteAll();
        reviewSessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    private MigrationExecution createExecutionInStatus(MigrationExecutionStatus initialStatus) {
        ReviewSession session = new ReviewSession(testUser, "sp_pl_" + UUID.randomUUID(), "Source PL", UUID.randomUUID());
        session.setStatus(ReviewSessionStatus.COMPLETED);
        session = reviewSessionRepository.save(session);

        ApprovedTrack track = new ApprovedTrack(
                0, "sp0", "Title", "Artist", "yt0", "Title", "Channel",
                Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 0
        );
        ApprovedMigrationContext context = new ApprovedMigrationContext(
                session.getId(), UUID.randomUUID(), testUser.getId(), session.getSourcePlaylistId(),
                "Source PL", "Target PL", "Desc", false, List.of(track), 1, 1, 0, Instant.now()
        );

        MigrationExecution execution = new MigrationExecution(testUser, session, context, "PL_dest_" + UUID.randomUUID(), "url");
        execution.setStatus(initialStatus);
        return executionRepository.saveAndFlush(execution);
    }

    @Test
    @DisplayName("Test 1: Multiple concurrent worker threads attempt to claim the same QUEUED execution -> EXACTLY ONE succeeds")
    void concurrentClaimExactlyOneWorkerSucceeds() throws Exception {
        MigrationExecution execution = createExecutionInStatus(MigrationExecutionStatus.QUEUED);
        UUID executionId = execution.getId();
        Long initialVersion = execution.getVersion() != null ? execution.getVersion() : 0L;

        int workerCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CyclicBarrier startBarrier = new CyclicBarrier(workerCount);
        CountDownLatch doneLatch = new CountDownLatch(workerCount);

        AtomicInteger successfulClaims = new AtomicInteger(0);
        AtomicInteger rejectedClaims = new AtomicInteger(0);

        DefaultTransactionDefinition txDef = new DefaultTransactionDefinition();
        txDef.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        for (int i = 0; i < workerCount; i++) {
            executor.submit(() -> {
                try {
                    startBarrier.await(5, TimeUnit.SECONDS);

                    TransactionTemplate txTemplate = new TransactionTemplate(transactionManager, txDef);
                    int claimed = txTemplate.execute(status -> executionRepository.claimExecutionRunningWithVersion(
                            executionId,
                            Set.of(MigrationExecutionStatus.QUEUED),
                            initialVersion,
                            Instant.now()
                    ));

                    if (claimed == 1) {
                        successfulClaims.incrementAndGet();
                    } else {
                        rejectedClaims.incrementAndGet();
                    }
                } catch (Exception e) {
                    // unexpected error
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        try {
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            // EXACTLY ONE worker claimed the execution
            assertThat(successfulClaims.get()).isEqualTo(1);
            // All other workers were rejected
            assertThat(rejectedClaims.get()).isEqualTo(workerCount - 1);

            // Verify database state: status is RUNNING and version was incremented
            MigrationExecution updated = executionRepository.findById(executionId).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
            assertThat(updated.getVersion()).isEqualTo(initialVersion + 1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Test 2: Stale version claim attempt is rejected and execution state remains unchanged")
    void staleVersionClaimRejected() {
        MigrationExecution execution = createExecutionInStatus(MigrationExecutionStatus.QUEUED);
        UUID executionId = execution.getId();
        Long actualVersion = execution.getVersion() != null ? execution.getVersion() : 0L;
        Long staleVersion = actualVersion + 99L; // Mismatched / stale version

        DefaultTransactionDefinition txDef = new DefaultTransactionDefinition();
        txDef.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager, txDef);

        int claimed = txTemplate.execute(status -> executionRepository.claimExecutionRunningWithVersion(
                executionId,
                Set.of(MigrationExecutionStatus.QUEUED),
                staleVersion,
                Instant.now()
        ));

        assertThat(claimed).isEqualTo(0);

        MigrationExecution unchanged = executionRepository.findById(executionId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(MigrationExecutionStatus.QUEUED);
        assertThat(unchanged.getVersion()).isEqualTo(actualVersion);
    }

    @Test
    @DisplayName("Test 3: Invalid status claim attempts (INITIALIZED, RUNNING, COMPLETED, FAILED, RECOVERY_REQUIRED) are rejected")
    void invalidStatusClaimAttemptsRejected() {
        List<MigrationExecutionStatus> nonQueuedStatuses = List.of(
                MigrationExecutionStatus.INITIALIZED,
                MigrationExecutionStatus.RUNNING,
                MigrationExecutionStatus.COMPLETED,
                MigrationExecutionStatus.FAILED,
                MigrationExecutionStatus.RECOVERY_REQUIRED
        );

        DefaultTransactionDefinition txDef = new DefaultTransactionDefinition();
        txDef.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager, txDef);

        for (MigrationExecutionStatus status : nonQueuedStatuses) {
            MigrationExecution execution = createExecutionInStatus(status);
            UUID executionId = execution.getId();
            Long version = execution.getVersion() != null ? execution.getVersion() : 0L;

            int claimed = txTemplate.execute(txStatus -> executionRepository.claimExecutionRunningWithVersion(
                    executionId,
                    Set.of(MigrationExecutionStatus.QUEUED),
                    version,
                    Instant.now()
            ));

            assertThat(claimed)
                    .as("Claiming execution in status %s with QUEUED-only filter must fail", status)
                    .isEqualTo(0);

            MigrationExecution unchanged = executionRepository.findById(executionId).orElseThrow();
            assertThat(unchanged.getStatus()).isEqualTo(status);
        }
    }
}
