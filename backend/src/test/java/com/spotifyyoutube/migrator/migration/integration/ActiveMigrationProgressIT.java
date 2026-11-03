package com.spotifyyoutube.migrator.migration.integration;

import com.spotifyyoutube.migrator.common.config.JpaConfig;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.progress.application.DefaultMigrationProgressService;
import com.spotifyyoutube.migrator.migration.progress.domain.MigrationProgressSnapshot;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL concurrent progress reading integration test during active migration execution (Phase 12G-F).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JpaConfig.class, DefaultMigrationProgressService.class})
@DisplayName("Phase 12G-F: PostgreSQL Progress Reading During Active Migration Integration Test")
public class ActiveMigrationProgressIT {

    private static final int TOTAL_TRACKS = 60;

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

    @BeforeEach
    void setUp() {
        testUser = userRepository.save(new User("active-progress-" + UUID.randomUUID() + "@example.com"));

        ReviewSession session = new ReviewSession(testUser, "sp_pl_" + UUID.randomUUID(), "Source 60 PL", UUID.randomUUID());
        session.setStatus(ReviewSessionStatus.COMPLETED);
        session = reviewSessionRepository.save(session);

        List<ApprovedTrack> tracks = new ArrayList<>();
        for (int i = 0; i < TOTAL_TRACKS; i++) {
            tracks.add(new ApprovedTrack(
                    i, "sp_" + i, "Title " + i, "Artist " + i, "yt_" + i, "Title " + i, "Channel " + i,
                    Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, i
            ));
        }

        ApprovedMigrationContext context = new ApprovedMigrationContext(
                session.getId(), UUID.randomUUID(), testUser.getId(), session.getSourcePlaylistId(),
                "Source 60 PL", "Target 60 PL", "Desc", false, tracks, TOTAL_TRACKS, TOTAL_TRACKS, 0, Instant.now()
        );

        MigrationExecution execution = new MigrationExecution(testUser, session, context, "PL_active_progress", "url");
        execution.queueForExecution();
        execution.claimExecution(); // Transition to RUNNING
        testExecution = executionRepository.saveAndFlush(execution);
    }

    @Test
    @DisplayName("Concurrent readers continuously poll progress while worker transitions 60 tracks -> all invariants hold")
    void concurrentProgressReadsDuringActiveWorkerTransitions() throws Exception {
        UUID executionId = testExecution.getId();
        UUID userId = testUser.getId();

        AtomicBoolean workerFinished = new AtomicBoolean(false);
        AtomicReference<Throwable> readerError = new AtomicReference<>(null);
        AtomicInteger totalSnapshotsSampled = new AtomicInteger(0);

        int readerCount = 4;
        ExecutorService readerPool = Executors.newFixedThreadPool(readerCount);
        ExecutorService workerPool = Executors.newSingleThreadExecutor();

        CountDownLatch workerStartedLatch = new CountDownLatch(1);
        DefaultTransactionDefinition txDef = new DefaultTransactionDefinition();
        txDef.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager, txDef);

        // Start worker thread that updates tracks sequentially in short transactions
        Future<?> workerFuture = workerPool.submit(() -> {
            workerStartedLatch.countDown();
            List<ExecutionTrackRecord> records = trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId);

            for (ExecutionTrackRecord track : records) {
                txTemplate.execute(status -> {
                    track.markAdding();
                    if (track.getTargetPosition() % 10 == 0) {
                        track.markFailed("Failed track");
                    } else if (track.getTargetPosition() % 15 == 0) {
                        track.markSkipped("Skipped track");
                    } else {
                        track.markAdded("yt_item_" + track.getTargetPosition());
                    }
                    trackRecordRepository.saveAndFlush(track);
                    return null;
                });
            }

            // Once all tracks are processed, complete the execution
            txTemplate.execute(status -> {
                MigrationExecution exec = executionRepository.findById(executionId).orElseThrow();
                exec.completeExecution(50, 6, 0, 4);
                executionRepository.saveAndFlush(exec);
                return null;
            });

            workerFinished.set(true);
        });

        workerStartedLatch.await(5, TimeUnit.SECONDS);

        // Start reader threads that poll progress while worker transitions tracks
        List<Future<?>> readerFutures = new ArrayList<>();
        for (int i = 0; i < readerCount; i++) {
            readerFutures.add(readerPool.submit(() -> {
                while (!workerFinished.get() && readerError.get() == null) {
                    try {
                        MigrationProgressSnapshot snapshot = progressService.getProgress(executionId, userId);
                        totalSnapshotsSampled.incrementAndGet();

                        // Invariant 1: Total tracks strictly equals 60
                        assertThat(snapshot.totalTracks()).isEqualTo(TOTAL_TRACKS);

                        // Invariant 2: Count conservation strictly holds:
                        // totalTracks == PENDING + ADDING + ADDED + FAILED + UNCERTAIN + SKIPPED + ROLLING_BACK + ROLLED_BACK
                        int sumOfStatuses = snapshot.pendingTracks()
                                + snapshot.addingTracks()
                                + snapshot.addedTracks()
                                + snapshot.failedTracks()
                                + snapshot.uncertainTracks()
                                + snapshot.skippedTracks()
                                + snapshot.rollingBackTracks()
                                + snapshot.rolledBackTracks();
                        assertThat(snapshot.totalTracks()).isEqualTo(sumOfStatuses);

                        // Invariant 3: Processed tracks count within [0, 60]
                        assertThat(snapshot.processedTracks()).isBetween(0, TOTAL_TRACKS);

                        // Invariant 4: Remaining tracks non-negative and exactly total - processed
                        assertThat(snapshot.remainingTracks()).isBetween(0, TOTAL_TRACKS);
                        assertThat(snapshot.remainingTracks()).isEqualTo(snapshot.totalTracks() - snapshot.processedTracks());

                        // Invariant 5: Progress percentage bounded in [0.0, 100.0]
                        assertThat(snapshot.progressPercentage()).isBetween(0.0, 100.0);
                    } catch (Throwable t) {
                        readerError.compareAndSet(null, t);
                    }
                }
            }));
        }

        // Wait for worker to finish
        workerFuture.get(15, TimeUnit.SECONDS);

        // Wait for readers to observe finish
        for (Future<?> f : readerFutures) {
            f.get(5, TimeUnit.SECONDS);
        }

        workerPool.shutdown();
        readerPool.shutdown();

        // Assert no error occurred during concurrent polling
        assertThat(readerError.get()).isNull();
        assertThat(totalSnapshotsSampled.get()).isGreaterThan(0);

        // Final snapshot validation: all 60 tracks processed
        MigrationProgressSnapshot finalSnapshot = progressService.getProgress(executionId, userId);
        assertThat(finalSnapshot.totalTracks()).isEqualTo(TOTAL_TRACKS);
        assertThat(finalSnapshot.processedTracks()).isEqualTo(TOTAL_TRACKS);
        assertThat(finalSnapshot.remainingTracks()).isEqualTo(0);
        assertThat(finalSnapshot.progressPercentage()).isEqualTo(100.0);
        assertThat(finalSnapshot.executionStatus()).isEqualTo(MigrationExecutionStatus.COMPLETED);
    }
}
