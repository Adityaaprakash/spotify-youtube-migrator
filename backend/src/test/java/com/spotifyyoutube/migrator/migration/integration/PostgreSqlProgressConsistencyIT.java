package com.spotifyyoutube.migrator.migration.integration;

import com.spotifyyoutube.migrator.common.config.JpaConfig;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.progress.application.DefaultMigrationProgressService;
import com.spotifyyoutube.migrator.migration.progress.domain.MigrationProgressSnapshot;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Integration test for PostgreSQL REPEATABLE_READ snapshot consistency and progress calculation (Phase 12G-B).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JpaConfig.class, DefaultMigrationProgressService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Phase 12G-B: PostgreSQL REPEATABLE_READ Progress Consistency Integration Test")
public class PostgreSqlProgressConsistencyIT {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReviewSessionRepository reviewSessionRepository;

    @Autowired
    private MigrationExecutionRepository executionRepository;

    @Autowired
    private ExecutionTrackRecordRepository trackRecordRepository;

    @Autowired
    private DefaultMigrationProgressService progressService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User testUser;
    private MigrationExecution testExecution;
    private List<ExecutionTrackRecord> persistedTracks;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        testUser = userRepository.save(new User("progress-it-" + UUID.randomUUID() + "@example.com"));

        ReviewSession session = new ReviewSession(testUser, "sp_pl_" + UUID.randomUUID(), "Source PL", UUID.randomUUID());
        session.setStatus(ReviewSessionStatus.COMPLETED);
        session = reviewSessionRepository.save(session);

        List<ApprovedTrack> approvedTracks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            approvedTracks.add(new ApprovedTrack(
                    i, "sp_" + i, "Title " + i, "Artist " + i, "yt_" + i, "Title " + i, "Channel " + i,
                    Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, i
            ));
        }

        ApprovedMigrationContext context = new ApprovedMigrationContext(
                session.getId(), UUID.randomUUID(), testUser.getId(), session.getSourcePlaylistId(),
                "Source PL", "Target PL", "Desc", false, approvedTracks, 5, 5, 0, Instant.now()
        );

        MigrationExecution execution = new MigrationExecution(testUser, session, context, "PL_dest_prog", "https://yt.com/pl");
        testExecution = executionRepository.saveAndFlush(execution);

        persistedTracks = trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(testExecution.getId());
        assertThat(persistedTracks).hasSize(5);
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

    @Test
    @DisplayName("Test 1 & 2: Stable REPEATABLE_READ snapshot survives concurrent modification, and subsequent read observes committed state")
    void stableRepeatableReadSnapshotAcrossConcurrentModification() throws Exception {
        UUID executionId = testExecution.getId();
        UUID userId = testUser.getId();

        DefaultTransactionDefinition repeatableReadDef = new DefaultTransactionDefinition();
        repeatableReadDef.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        repeatableReadDef.setReadOnly(true);

        TransactionTemplate repeatableReadTemplate = new TransactionTemplate(transactionManager, repeatableReadDef);

        CountDownLatch firstReadDone = new CountDownLatch(1);
        CountDownLatch concurrentWriteCommitted = new CountDownLatch(1);
        ExecutorService writerExecutor = Executors.newSingleThreadExecutor();

        AtomicReference<Throwable> readerError = new AtomicReference<>(null);

        try {
            Future<Void> readerFuture = writerExecutor.submit(() -> {
                try {
                    repeatableReadTemplate.execute(status -> {
                        // First read inside REPEATABLE_READ transaction: all 5 PENDING
                        MigrationProgressSnapshot snapshot1 = progressService.getProgress(executionId, userId);
                        assertThat(snapshot1.totalTracks()).isEqualTo(5);
                        assertThat(snapshot1.pendingTracks()).isEqualTo(5);
                        assertThat(snapshot1.addedTracks()).isEqualTo(0);
                        assertThat(snapshot1.processedTracks()).isEqualTo(0);
                        assertThat(snapshot1.progressPercentage()).isEqualTo(0.0);

                        firstReadDone.countDown();

                        // Wait for the concurrent writer to commit changes
                        try {
                            boolean awaited = concurrentWriteCommitted.await(5, TimeUnit.SECONDS);
                            assertThat(awaited).isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }

                        // Second read inside the SAME REPEATABLE_READ transaction:
                        // In PostgreSQL MVCC, this transaction MUST NOT see the committed writes from the other transaction!
                        MigrationProgressSnapshot snapshot2 = progressService.getProgress(executionId, userId);
                        assertThat(snapshot2.totalTracks()).isEqualTo(5);
                        assertThat(snapshot2.pendingTracks()).isEqualTo(5);
                        assertThat(snapshot2.addedTracks()).isEqualTo(0);
                        assertThat(snapshot2.processedTracks()).isEqualTo(0);
                        assertThat(snapshot2.progressPercentage()).isEqualTo(0.0);

                        return null;
                    });
                } catch (Throwable t) {
                    readerError.compareAndSet(null, t);
                    firstReadDone.countDown();
                    throw t;
                }
                return null;
            });

            // Wait for the reader to complete its first read
            boolean read1Done = firstReadDone.await(5, TimeUnit.SECONDS);
            if (readerError.get() != null) {
                fail("Reader thread failed during execution: " + readerError.get().getMessage(), readerError.get());
            }
            assertThat(read1Done).isTrue();

            // Perform concurrent write in a separate transaction and commit
            DefaultTransactionDefinition writeDef = new DefaultTransactionDefinition();
            writeDef.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            TransactionTemplate writeTemplate = new TransactionTemplate(transactionManager, writeDef);

            writeTemplate.execute(status -> {
                List<ExecutionTrackRecord> tracks = trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId);
                // Mark track 0 and 1 as ADDED
                tracks.get(0).markAdding();
                tracks.get(0).markAdded("yt_item_0");
                tracks.get(1).markAdding();
                tracks.get(1).markAdded("yt_item_1");
                trackRecordRepository.saveAllAndFlush(tracks);
                return null;
            });

            // Signal the reader that the write is committed
            concurrentWriteCommitted.countDown();

            // Reader transaction completes and asserts stable snapshot
            readerFuture.get(5, TimeUnit.SECONDS);

            // Subsequent read in a NEW transaction must observe the committed writes!
            TransactionTemplate newReadTemplate = new TransactionTemplate(transactionManager, repeatableReadDef);
            newReadTemplate.execute(status -> {
                MigrationProgressSnapshot subsequentSnapshot = progressService.getProgress(executionId, userId);
                assertThat(subsequentSnapshot.totalTracks()).isEqualTo(5);
                assertThat(subsequentSnapshot.addedTracks()).isEqualTo(2);
                assertThat(subsequentSnapshot.pendingTracks()).isEqualTo(3);
                assertThat(subsequentSnapshot.processedTracks()).isEqualTo(2);
                assertThat(subsequentSnapshot.remainingTracks()).isEqualTo(3);
                assertThat(subsequentSnapshot.progressPercentage()).isEqualTo(40.0);
                return null;
            });

        } finally {
            writerExecutor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Test 3, 4, 5, 6: Invariants for count conservation, processed count, remaining count, and progress percentage")
    void countConservationAndProgressCalculationInvariants() {
        UUID executionId = testExecution.getId();
        UUID userId = testUser.getId();

        // Transition track statuses:
        // Track 0 -> ADDED
        // Track 1 -> FAILED
        // Track 2 -> UNCERTAIN
        // Track 3 -> SKIPPED
        // Track 4 -> PENDING
        List<ExecutionTrackRecord> tracks = trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId);
        tracks.get(0).markAdding();
        tracks.get(0).markAdded("item_0");
        tracks.get(1).markAdding();
        tracks.get(1).markFailed("Quota exceeded");
        tracks.get(2).markAdding();
        tracks.get(2).markUncertain("Socket timeout");
        tracks.get(3).markSkipped("User unselected");
        trackRecordRepository.saveAllAndFlush(tracks);

        MigrationProgressSnapshot snapshot = progressService.getProgress(executionId, userId);

        // Test 3: Count conservation
        // totalTracks = PENDING + ADDING + ADDED + FAILED + UNCERTAIN + SKIPPED + ROLLING_BACK + ROLLED_BACK
        int sumOfAllStatuses = snapshot.pendingTracks()
                + snapshot.addingTracks()
                + snapshot.addedTracks()
                + snapshot.failedTracks()
                + snapshot.uncertainTracks()
                + snapshot.skippedTracks()
                + snapshot.rollingBackTracks()
                + snapshot.rolledBackTracks();
        assertThat(snapshot.totalTracks()).isEqualTo(sumOfAllStatuses);
        assertThat(snapshot.totalTracks()).isEqualTo(5);

        // Test 4: Processed count
        // processedTracks = ADDED + FAILED + UNCERTAIN + SKIPPED + ROLLED_BACK
        int expectedProcessed = snapshot.addedTracks()
                + snapshot.failedTracks()
                + snapshot.uncertainTracks()
                + snapshot.skippedTracks()
                + snapshot.rolledBackTracks();
        assertThat(snapshot.processedTracks()).isEqualTo(expectedProcessed);
        assertThat(snapshot.processedTracks()).isEqualTo(4);

        // Test 5: Remaining count
        // remainingTracks = totalTracks - processedTracks
        assertThat(snapshot.remainingTracks()).isEqualTo(snapshot.totalTracks() - snapshot.processedTracks());
        assertThat(snapshot.remainingTracks()).isEqualTo(1);

        // Test 6: Progress percentage
        // 4 processed out of 5 = 80.0%
        assertThat(snapshot.progressPercentage()).isEqualTo(80.0);
    }
}
