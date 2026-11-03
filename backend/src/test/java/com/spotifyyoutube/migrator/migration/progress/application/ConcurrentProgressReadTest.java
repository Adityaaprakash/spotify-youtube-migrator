package com.spotifyyoutube.migrator.migration.progress.application;

import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.TrackStatusCount;
import com.spotifyyoutube.migrator.migration.progress.domain.MigrationProgressSnapshot;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Concurrency test verifying progress snapshot consistency while tracks are actively transitioning (Phase 12F).
 * Uses deterministic synchronization barriers and latches rather than arbitrary sleeps.
 */
@DisplayName("Phase 12F: Concurrent Progress Read Testing")
class ConcurrentProgressReadTest {

    private MigrationExecutionRepository executionRepository;
    private ExecutionTrackRecordRepository trackRecordRepository;
    private DefaultMigrationProgressService progressService;

    private UUID userId;
    private UUID executionId;
    private MigrationExecution execution;

    private static final int TOTAL_TRACKS = 50;

    @BeforeEach
    void setUp() {
        executionRepository = mock(MigrationExecutionRepository.class);
        trackRecordRepository = mock(ExecutionTrackRecordRepository.class);
        progressService = new DefaultMigrationProgressService(executionRepository, trackRecordRepository);

        userId = UUID.randomUUID();
        User testUser = new User("tester@example.com");
        testUser.setId(userId);

        UUID sessionId = UUID.randomUUID();
        ReviewSession reviewSession = new ReviewSession(testUser, "sp_playlist", "Title", UUID.randomUUID());
        reviewSession.setId(sessionId);
        reviewSession.setStatus(ReviewSessionStatus.COMPLETED);

        List<ApprovedTrack> tracks = new ArrayList<>();
        for (int i = 0; i < TOTAL_TRACKS; i++) {
            tracks.add(new ApprovedTrack(i, "sp" + i, "Title " + i, "Artist", "yt" + i, "YT Title", "Chan", Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, i));
        }

        ApprovedMigrationContext context = new ApprovedMigrationContext(
                sessionId, UUID.randomUUID(), userId, "sp_playlist", "Title",
                "Target", "Desc", false, tracks, TOTAL_TRACKS, TOTAL_TRACKS, 0, Instant.now()
        );

        executionId = UUID.randomUUID();
        execution = new MigrationExecution(testUser, reviewSession, context, "PL_target", "https://music.youtube.com/playlist?list=PL_target");
        execution.setId(executionId);
        execution.setStatus(MigrationExecutionStatus.RUNNING);

        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));
    }

    @Test
    @DisplayName("Concurrent reads observe mathematically consistent snapshots while worker transitions tracks")
    void concurrentReadsObserveConsistentSnapshots() throws Exception {
        int readerThreadCount = 8;
        int transitionsCount = 50;

        ExecutorService executor = Executors.newFixedThreadPool(readerThreadCount + 1);
        CyclicBarrier startBarrier = new CyclicBarrier(readerThreadCount + 1);
        CountDownLatch completionLatch = new CountDownLatch(readerThreadCount + 1);

        AtomicBoolean workerFinished = new AtomicBoolean(false);
        AtomicInteger totalReadOperations = new AtomicInteger(0);
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();

        // Simulated thread-safe track state store
        AtomicInteger pendingCount = new AtomicInteger(TOTAL_TRACKS);
        AtomicInteger addingCount = new AtomicInteger(0);
        AtomicInteger addedCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);

        // Dynamic repository mock reflecting state in atomic store
        when(trackRecordRepository.countTracksByStatusForExecution(executionId)).thenAnswer(inv -> {
            int pending = pendingCount.get();
            int adding = addingCount.get();
            int added = addedCount.get();
            int failed = failedCount.get();

            List<TrackStatusCount> counts = new ArrayList<>();
            if (pending > 0) counts.add(new TrackStatusCount(ExecutionTrackStatus.PENDING, (long) pending));
            if (adding > 0) counts.add(new TrackStatusCount(ExecutionTrackStatus.ADDING, (long) adding));
            if (added > 0) counts.add(new TrackStatusCount(ExecutionTrackStatus.ADDED, (long) added));
            if (failed > 0) counts.add(new TrackStatusCount(ExecutionTrackStatus.FAILED, (long) failed));
            return counts;
        });

        // 1. Worker Thread: transitions tracks one by one from PENDING -> ADDING -> ADDED (or FAILED)
        executor.submit(() -> {
            try {
                startBarrier.await();
                for (int i = 0; i < transitionsCount; i++) {
                    // PENDING -> ADDING
                    pendingCount.decrementAndGet();
                    addingCount.incrementAndGet();

                    // Simulating atomic worker state transition: ADDING -> ADDED (or FAILED for odd i)
                    addingCount.decrementAndGet();
                    if (i % 5 == 0) {
                        failedCount.incrementAndGet();
                    } else {
                        addedCount.incrementAndGet();
                    }
                }
            } catch (Throwable t) {
                readerFailure.compareAndSet(null, t);
            } finally {
                workerFinished.set(true);
                completionLatch.countDown();
            }
        });

        // 2. Reader Threads: repeatedly poll progress and assert mathematical invariants
        List<MigrationProgressSnapshot> collectedSnapshots = Collections.synchronizedList(new ArrayList<>());

        for (int r = 0; r < readerThreadCount; r++) {
            executor.submit(() -> {
                try {
                    startBarrier.await();
                    while (!workerFinished.get() || totalReadOperations.get() < 100) {
                        MigrationProgressSnapshot snapshot = progressService.getProgress(executionId, userId);
                        collectedSnapshots.add(snapshot);
                        totalReadOperations.incrementAndGet();

                        // Strict Invariant Assertions on each snapshot:
                        int sumOfStates = snapshot.pendingTracks()
                                + snapshot.addingTracks()
                                + snapshot.addedTracks()
                                + snapshot.failedTracks()
                                + snapshot.uncertainTracks()
                                + snapshot.skippedTracks()
                                + snapshot.rollingBackTracks()
                                + snapshot.rolledBackTracks();

                        assertThat(sumOfStates)
                                .withFailMessage("Count conservation violated: totalTracks=%d != sumOfStates=%d", snapshot.totalTracks(), sumOfStates)
                                .isEqualTo(TOTAL_TRACKS);

                        assertThat(snapshot.totalTracks()).isEqualTo(TOTAL_TRACKS);

                        int processed = snapshot.addedTracks()
                                + snapshot.failedTracks()
                                + snapshot.uncertainTracks()
                                + snapshot.skippedTracks()
                                + snapshot.rolledBackTracks();

                        assertThat(snapshot.processedTracks()).isEqualTo(processed);
                        assertThat(snapshot.remainingTracks()).isEqualTo(TOTAL_TRACKS - processed);
                        assertThat(snapshot.remainingTracks()).isBetween(0, TOTAL_TRACKS);
                        assertThat(snapshot.progressPercentage()).isBetween(0.0, 100.0);
                        assertThat(snapshot.pendingTracks()).isGreaterThanOrEqualTo(0);
                        assertThat(snapshot.addingTracks()).isGreaterThanOrEqualTo(0);
                        assertThat(snapshot.addedTracks()).isGreaterThanOrEqualTo(0);
                        assertThat(snapshot.failedTracks()).isGreaterThanOrEqualTo(0);
                    }
                } catch (Throwable t) {
                    readerFailure.compareAndSet(null, t);
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        completionLatch.await();
        executor.shutdown();

        assertThat(readerFailure.get()).isNull();
        assertThat(totalReadOperations.get()).isGreaterThan(50);
        assertThat(collectedSnapshots).isNotEmpty();

        // Verify strictly read-only: no database writes performed
        verify(executionRepository, never()).save(any());
        verify(executionRepository, never()).saveAndFlush(any());
        verify(trackRecordRepository, never()).save(any());
        verify(trackRecordRepository, never()).saveAndFlush(any());
    }
}
