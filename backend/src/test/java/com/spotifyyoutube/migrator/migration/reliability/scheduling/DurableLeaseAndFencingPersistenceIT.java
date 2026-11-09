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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL database integration tests verifying Flyway migration V10, durable lease persistence,
 * generational fencing queries, and track-level retry scheduling (Phase 13D-A).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JpaConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Phase 13D-A: PostgreSQL Durable Lease & Fencing Persistence Integration Test")
public class DurableLeaseAndFencingPersistenceIT {

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
    private ReviewSession testSession;
    private MigrationExecution execution;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        testUser = userRepository.save(new User("lease-persistence@example.com"));

        testSession = new ReviewSession(
                testUser,
                "sp_playlist_lease_it",
                "Lease Persistence Playlist",
                UUID.randomUUID()
        );
        testSession = reviewSessionRepository.save(testSession);

        ApprovedTrack track0 = new ApprovedTrack(
                0, "sp_t0", "Song Zero", "Artist A",
                "yt_t0", "Song Zero (Video)", "Channel A",
                Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 0
        );
        ApprovedTrack track1 = new ApprovedTrack(
                1, "sp_t1", "Song One", "Artist B",
                "yt_t1", "Song One (Video)", "Channel B",
                Duration.ofMinutes(4), ReviewDecisionSource.USER_SELECTED, 1
        );

        ApprovedMigrationContext context = new ApprovedMigrationContext(
                testSession.getId(), UUID.randomUUID(), testUser.getId(),
                "sp_playlist_lease_it", "Lease Persistence Playlist", "Target Playlist",
                "Description", false, List.of(track0, track1),
                2, 2, 0, Instant.now()
        );

        execution = new MigrationExecution(
                testUser, testSession, context,
                "PL_youtube_persistence_123", "https://music.youtube.com/playlist?list=PL_youtube_persistence_123",
                MigrationExecutionStatus.QUEUED
        );
        execution = executionRepository.saveAndFlush(execution);
        if (entityManager != null) {
            entityManager.clear();
        }
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

    @Test
    @DisplayName("Execution lease metadata persists and reloads accurately from PostgreSQL")
    void persistAndReloadExecutionLeaseFields() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant leaseUntil = now.plusSeconds(60);
        Instant nextAttempt = now.plusSeconds(30);

        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            exec.acquireLease("worker-node-1", leaseUntil, now);
            exec.setNextAttemptAt(nextAttempt);
            executionRepository.saveAndFlush(exec);
        });

        MigrationExecution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getLeaseOwner()).isEqualTo("worker-node-1");
        assertThat(reloaded.getLeaseGeneration()).isEqualTo(1L);
        assertThat(reloaded.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
        assertThat(reloaded.getLeaseUntil()).isEqualTo(leaseUntil);
        assertThat(reloaded.getHeartbeatAt()).isEqualTo(now);
        assertThat(reloaded.getNextAttemptAt()).isEqualTo(nextAttempt);
    }

    @Test
    @DisplayName("Track record retry metadata persists and reloads accurately from PostgreSQL")
    void persistAndReloadTrackRetryFields() {
        ExecutionTrackRecord track = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 0).orElseThrow();
        Instant retryAt = Instant.now().plusSeconds(25).truncatedTo(ChronoUnit.MICROS);

        track.markAdding();
        track.scheduleRetry(retryAt, "RATE_LIMITED", 25000L);
        track.setMaxAttempts(5);
        track = trackRecordRepository.saveAndFlush(track);

        ExecutionTrackRecord reloaded = trackRecordRepository.findById(track.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);
        assertThat(reloaded.getNextAttemptAt()).isEqualTo(retryAt);
        assertThat(reloaded.getLastFailureClassification()).isEqualTo("RATE_LIMITED");
        assertThat(reloaded.getRetryBackoffMs()).isEqualTo(25000L);
        assertThat(reloaded.getMaxAttempts()).isEqualTo(5);
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Conditional heartbeat renewal succeeds for active owner using PostgreSQL time and rejects stale/expired lease")
    void conditionalHeartbeatRenewal() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant leaseUntil = now.plusSeconds(60);
        AtomicLong leaseGen = new AtomicLong();
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            leaseGen.set(exec.acquireLease("worker-1", leaseUntil, now));
            executionRepository.saveAndFlush(exec);
        });
        long gen = leaseGen.get();

        // 1. Current owner with matching generation succeeds (extends by 60s from DB time)
        int updated = executionRepository.renewLeaseHeartbeat(
                execution.getId(),
                "worker-1",
                gen,
                60L
        );
        assertThat(updated).isEqualTo(1);

        MigrationExecution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getHeartbeatAt()).isNotNull();
        assertThat(reloaded.getLeaseUntil()).isAfter(Instant.now());

        // 2. Stale generation is rejected (0 rows updated)
        int staleUpdate = executionRepository.renewLeaseHeartbeat(
                execution.getId(),
                "worker-1",
                gen + 10,
                60L
        );
        assertThat(staleUpdate).isEqualTo(0);

        // 3. Mismatched worker ID is rejected (0 rows updated)
        int imposterUpdate = executionRepository.renewLeaseHeartbeat(
                execution.getId(),
                "imposter-worker",
                gen,
                60L
        );
        assertThat(imposterUpdate).isEqualTo(0);

        // 4. Expired lease cannot be renewed even with matching worker and generation (returns 0)
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            exec.setLeaseUntil(Instant.now().minusSeconds(10));
            executionRepository.saveAndFlush(exec);
        });
        int expiredRenewal = executionRepository.renewLeaseHeartbeat(
                execution.getId(),
                "worker-1",
                gen,
                60L
        );
        assertThat(expiredRenewal).isEqualTo(0);
    }

    @Test
    @DisplayName("Conditional lease release transitions status to QUEUED with next_attempt_at and clears lease")
    void conditionalLeaseRelease() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant leaseUntil = now.plusSeconds(60);
        AtomicLong leaseGen = new AtomicLong();
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            leaseGen.set(exec.acquireLease("worker-1", leaseUntil, now));
            executionRepository.saveAndFlush(exec);
        });
        long gen = leaseGen.get();

        Instant nextAttempt = now.plusSeconds(45);
        int released = executionRepository.releaseLease(
                execution.getId(),
                "worker-1",
                gen,
                MigrationExecutionStatus.QUEUED,
                nextAttempt
        );
        assertThat(released).isEqualTo(1);

        MigrationExecution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MigrationExecutionStatus.QUEUED);
        assertThat(reloaded.getLeaseOwner()).isNull();
        assertThat(reloaded.getLeaseUntil()).isNull();
        assertThat(reloaded.getNextAttemptAt()).isEqualTo(nextAttempt);
        assertThat(reloaded.getLeaseGeneration()).isEqualTo(gen);

        // Releasing again with stale generation returns 0
        int staleRelease = executionRepository.releaseLease(
                execution.getId(),
                "worker-1",
                gen - 1,
                MigrationExecutionStatus.QUEUED,
                nextAttempt
        );
        assertThat(staleRelease).isEqualTo(0);
    }

    @Test
    @DisplayName("Fenced track mutation succeeds with matching generation and rejects zombie worker write")
    void fencedTrackStatusUpdate() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AtomicLong leaseGen = new AtomicLong();
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            leaseGen.set(exec.acquireLease("worker-1", now.plusSeconds(60), now));
            executionRepository.saveAndFlush(exec);
        });
        long gen = leaseGen.get();

        ExecutionTrackRecord track0 = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 0).orElseThrow();
        track0.markAdding();
        trackRecordRepository.saveAndFlush(track0);
        entityManager.clear();

        // 1. Mutation with matching parent lease generation succeeds
        int updated = trackRecordRepository.updateTrackStatusWithFencing(
                track0.getId(),
                execution.getId(),
                gen,
                ExecutionTrackStatus.ADDED,
                "yt_item_confirmed_1",
                null,
                now
        );
        assertThat(updated).isEqualTo(1);

        entityManager.clear();
        ExecutionTrackRecord reloaded = trackRecordRepository.findById(track0.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
        assertThat(reloaded.getDestinationPlaylistItemId()).isEqualTo("yt_item_confirmed_1");
        assertThat(reloaded.getVersion()).isGreaterThan(0L);

        // 2. Simulate zombie worker write with stale generation (e.g. gen - 1)
        ExecutionTrackRecord track1 = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 1).orElseThrow();
        track1.markAdding();
        trackRecordRepository.saveAndFlush(track1);
        entityManager.clear();

        int zombieWrite = trackRecordRepository.updateTrackStatusWithFencing(
                track1.getId(),
                execution.getId(),
                gen - 1, // stale fencing token!
                ExecutionTrackStatus.ADDED,
                "yt_item_zombie",
                null,
                now
        );
        assertThat(zombieWrite).isEqualTo(0);

        entityManager.clear();
        ExecutionTrackRecord untouched = trackRecordRepository.findById(track1.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(ExecutionTrackStatus.ADDING); // unchanged!
    }

    @Test
    @DisplayName("Fenced retry scheduling updates track to PENDING when generation matches and rejects stale token")
    void fencedTrackRetryScheduling() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AtomicLong leaseGen = new AtomicLong();
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            leaseGen.set(exec.acquireLease("worker-1", now.plusSeconds(60), now));
            executionRepository.saveAndFlush(exec);
        });
        long gen = leaseGen.get();

        ExecutionTrackRecord track = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 0).orElseThrow();
        track.markAdding();
        trackRecordRepository.saveAndFlush(track);
        entityManager.clear();

        Instant beforeSchedule = Instant.now();
        Instant retryAt = beforeSchedule.plusSeconds(20);
        int scheduled = trackRecordRepository.scheduleTrackRetryWithFencing(
                track.getId(),
                execution.getId(),
                gen,
                retryAt,
                "TRANSIENT_SERVER_ERROR",
                20000L
        );
        Instant afterSchedule = Instant.now();
        assertThat(scheduled).isEqualTo(1);

        entityManager.clear();
        ExecutionTrackRecord reloaded = trackRecordRepository.findById(track.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);
        assertThat(reloaded.getNextAttemptAt())
                .isNotNull()
                .isAfterOrEqualTo(beforeSchedule.plusSeconds(19))
                .isBeforeOrEqualTo(afterSchedule.plusSeconds(22));
        assertThat(reloaded.getLastFailureClassification()).isEqualTo("TRANSIENT_SERVER_ERROR");
        assertThat(reloaded.getRetryBackoffMs()).isEqualTo(20000L);
        assertThat(reloaded.getVersion()).isGreaterThan(0L);

        // Stale token returns 0
        int staleScheduled = trackRecordRepository.scheduleTrackRetryWithFencing(
                track.getId(),
                execution.getId(),
                gen + 5, // stale token
                retryAt.plusSeconds(10),
                "RATE_LIMITED",
                30000L
        );
        assertThat(staleScheduled).isEqualTo(0);

        // Attempting to reschedule an already-settled ADDED track returns 0
        // Follow legitimate domain transition: PENDING -> markAdding() -> ADDING -> markAdded() -> ADDED
        reloaded.markAdding();
        reloaded.markAdded("yt_confirmed_skip_reschedule");
        trackRecordRepository.saveAndFlush(reloaded);
        entityManager.clear();

        int completedReschedule = trackRecordRepository.scheduleTrackRetryWithFencing(
                track.getId(),
                execution.getId(),
                gen,
                retryAt.plusSeconds(30),
                "RATE_LIMITED",
                30000L
        );
        assertThat(completedReschedule).isEqualTo(0);
    }

    @Test
    @DisplayName("findDuePendingTracks and findEarliestDeferredRetry correctly filter based on PostgreSQL database time")
    void trackSchedulingQueries() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ExecutionTrackRecord track0 = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 0).orElseThrow();
        ExecutionTrackRecord track1 = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 1).orElseThrow();

        // Track 0: due immediately (nextAttemptAt = null)
        track0.setNextAttemptAt(null);
        trackRecordRepository.saveAndFlush(track0);

        // Track 1: deferred to 30s in future
        Instant futureRetry = now.plusSeconds(30);
        track1.scheduleRetry(futureRetry, "RATE_LIMITED", 30000L);
        trackRecordRepository.saveAndFlush(track1);

        // 1. findDuePendingTracks evaluates against CURRENT_TIMESTAMP -> returns only Track 0
        List<ExecutionTrackRecord> dueTracks = trackRecordRepository.findDuePendingTracks(execution.getId());
        assertThat(dueTracks).hasSize(1);
        assertThat(dueTracks.get(0).getId()).isEqualTo(track0.getId());

        // 2. findEarliestDeferredRetry evaluates against CURRENT_TIMESTAMP -> returns futureRetry
        Optional<Instant> earliest = trackRecordRepository.findEarliestDeferredRetry(execution.getId());
        assertThat(earliest).isPresent();
        assertThat(earliest.get()).isEqualTo(futureRetry);
    }

    @Test
    @DisplayName("Concurrency race: stale worker track mutation is safely rejected when new worker increments generation")
    void concurrentLeaseClaimVsStaleTrackMutationRace() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AtomicLong leaseGen = new AtomicLong();
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution execSetup = executionRepository.findById(execution.getId()).orElseThrow();
            leaseGen.set(execSetup.acquireLease("worker-1", now.minusMillis(1), now));
            executionRepository.saveAndFlush(execSetup);
        });
        long gen1 = leaseGen.get();

        ExecutionTrackRecord track0 = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 0).orElseThrow();
        track0.markAdding();
        trackRecordRepository.saveAndFlush(track0);
        entityManager.clear();

        AtomicInteger worker1UpdateCount = new AtomicInteger(-1);
        AtomicInteger worker2ClaimCount = new AtomicInteger(-1);

        // Thread 2 (Worker 2): claims the execution and increments lease generation to 2
        CompletableFuture<Void> worker2Claim = CompletableFuture.runAsync(() -> {
            txTemplate.executeWithoutResult(status -> {
                int claimed = executionRepository.updateStatusWithFencing(
                        execution.getId(),
                        "worker-1",
                        gen1,
                        MigrationExecutionStatus.RUNNING
                );
                // Worker 2 takes over ownership and increments generation
                MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
                exec.acquireLease("worker-2", Instant.now().plusSeconds(60), Instant.now());
                executionRepository.saveAndFlush(exec);
                worker2ClaimCount.set(1);
            });
        });

        // Ensure Worker 2's takeover transaction completes
        worker2Claim.join();
        assertThat(worker2ClaimCount.get()).isEqualTo(1);

        // Thread 1 (Worker 1): stale worker wakes up and attempts to update track0 with old gen1
        CompletableFuture<Void> worker1Mutation = CompletableFuture.runAsync(() -> {
            txTemplate.executeWithoutResult(status -> {
                int updated = trackRecordRepository.updateTrackStatusWithFencing(
                        track0.getId(),
                        execution.getId(),
                        gen1, // stale generation 1!
                        ExecutionTrackStatus.ADDED,
                        "yt_zombie_item_id",
                        null,
                        Instant.now()
                );
                worker1UpdateCount.set(updated);
            });
        });

        worker1Mutation.join();

        // Stale update was fenced out by PostgreSQL FOR SHARE check
        assertThat(worker1UpdateCount.get()).isEqualTo(0);

        // Verify track record in database was NOT modified by the stale worker
        entityManager.clear();
        ExecutionTrackRecord reloadedTrack = trackRecordRepository.findById(track0.getId()).orElseThrow();
        assertThat(reloadedTrack.getStatus()).isEqualTo(ExecutionTrackStatus.ADDING);
        assertThat(reloadedTrack.getDestinationPlaylistItemId()).isNull();
    }

    @Test
    @DisplayName("Concurrency safety: parallel track updates under the same valid generation succeed concurrently")
    void parallelTrackUpdatesUnderSameGeneration() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AtomicLong leaseGen = new AtomicLong();
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            leaseGen.set(exec.acquireLease("worker-1", now.plusSeconds(60), now));
            executionRepository.saveAndFlush(exec);
        });
        long gen = leaseGen.get();

        ExecutionTrackRecord track0 = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 0).orElseThrow();
        ExecutionTrackRecord track1 = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 1).orElseThrow();
        track0.markAdding();
        track1.markAdding();
        trackRecordRepository.saveAndFlush(track0);
        trackRecordRepository.saveAndFlush(track1);
        entityManager.clear();

        AtomicInteger count0 = new AtomicInteger(-1);
        AtomicInteger count1 = new AtomicInteger(-1);

        // Two worker threads update distinct tracks for the same active execution in parallel
        CompletableFuture<Void> update0 = CompletableFuture.runAsync(() -> {
            txTemplate.executeWithoutResult(status -> {
                int res = trackRecordRepository.updateTrackStatusWithFencing(
                        track0.getId(),
                        execution.getId(),
                        gen,
                        ExecutionTrackStatus.ADDED,
                        "yt_item_0",
                        null,
                        Instant.now()
                );
                count0.set(res);
            });
        });

        CompletableFuture<Void> update1 = CompletableFuture.runAsync(() -> {
            txTemplate.executeWithoutResult(status -> {
                int res = trackRecordRepository.updateTrackStatusWithFencing(
                        track1.getId(),
                        execution.getId(),
                        gen,
                        ExecutionTrackStatus.ADDED,
                        "yt_item_1",
                        null,
                        Instant.now()
                );
                count1.set(res);
            });
        });

        CompletableFuture.allOf(update0, update1).join();

        // Both parallel track updates succeeded with shared parent lock
        assertThat(count0.get()).isEqualTo(1);
        assertThat(count1.get()).isEqualTo(1);

        entityManager.clear();
        ExecutionTrackRecord reloaded0 = trackRecordRepository.findById(track0.getId()).orElseThrow();
        ExecutionTrackRecord reloaded1 = trackRecordRepository.findById(track1.getId()).orElseThrow();
        assertThat(reloaded0.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
        assertThat(reloaded0.getDestinationPlaylistItemId()).isEqualTo("yt_item_0");
        assertThat(reloaded1.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
        assertThat(reloaded1.getDestinationPlaylistItemId()).isEqualTo("yt_item_1");
    }

    @Test
    @DisplayName("Database-level release guard: releaseLease returns 0 and refuses release if any track is ADDING or ROLLING_BACK")
    void releaseLeaseBlockedWhenTrackInFlightInDatabase() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AtomicLong leaseGen = new AtomicLong();
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            leaseGen.set(exec.acquireLease("worker-1", now.plusSeconds(60), now));
            executionRepository.saveAndFlush(exec);
        });
        long gen = leaseGen.get();

        ExecutionTrackRecord track0 = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 0).orElseThrow();
        track0.markAdding();
        trackRecordRepository.saveAndFlush(track0);
        entityManager.clear();

        Instant nextAttempt = now.plusSeconds(30);

        // 1. Attempting release while track0 is ADDING in database returns 0
        int blockedRelease = executionRepository.releaseLease(
                execution.getId(),
                "worker-1",
                gen,
                MigrationExecutionStatus.QUEUED,
                nextAttempt
        );
        assertThat(blockedRelease).isEqualTo(0);

        entityManager.clear();
        MigrationExecution untouched = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
        assertThat(untouched.getLeaseOwner()).isEqualTo("worker-1");

        // 2. Settle track0 to ADDED via fenced mutation
        int updated = trackRecordRepository.updateTrackStatusWithFencing(
                track0.getId(),
                execution.getId(),
                gen,
                ExecutionTrackStatus.ADDED,
                "yt_item_settled",
                null,
                Instant.now()
        );
        assertThat(updated).isEqualTo(1);

        // 3. Now with zero in-flight tracks, release succeeds
        int successfulRelease = executionRepository.releaseLease(
                execution.getId(),
                "worker-1",
                gen,
                MigrationExecutionStatus.QUEUED,
                nextAttempt
        );
        assertThat(successfulRelease).isEqualTo(1);

        entityManager.clear();
        MigrationExecution released = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(MigrationExecutionStatus.QUEUED);
        assertThat(released.getLeaseOwner()).isNull();
        assertThat(released.getLeaseUntil()).isNull();
    }

    @Test
    @DisplayName("Fenced track claiming: claimTrackAddingWithFencing and claimTrackRollingBackWithFencing enforce generation and atomic transition")
    void fencedTrackClaimAddingAndRollingBack() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AtomicLong leaseGen = new AtomicLong();
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            leaseGen.set(exec.acquireLease("worker-1", now.plusSeconds(60), now));
            executionRepository.saveAndFlush(exec);
        });
        long gen = leaseGen.get();
        entityManager.clear();

        ExecutionTrackRecord track0 = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 0).orElseThrow();
        ExecutionTrackRecord track1 = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 1).orElseThrow();

        // 1. Claim track0 for ADDING from PENDING with matching generation
        int claimedAdding = trackRecordRepository.claimTrackAddingWithFencing(track0.getId(), execution.getId(), gen);
        assertThat(claimedAdding).isEqualTo(1);

        entityManager.clear();
        ExecutionTrackRecord reloaded0 = trackRecordRepository.findById(track0.getId()).orElseThrow();
        assertThat(reloaded0.getStatus()).isEqualTo(ExecutionTrackStatus.ADDING);
        assertThat(reloaded0.getAttemptCount()).isEqualTo(1);
        assertThat(reloaded0.getStartedAt()).isNotNull();
        assertThat(reloaded0.getVersion()).isGreaterThan(0L);

        // Claiming again from ADDING returns 0
        int alreadyClaimed = trackRecordRepository.claimTrackAddingWithFencing(track0.getId(), execution.getId(), gen);
        assertThat(alreadyClaimed).isEqualTo(0);

        // Claiming with stale generation returns 0
        int staleClaim = trackRecordRepository.claimTrackAddingWithFencing(track1.getId(), execution.getId(), gen + 10);
        assertThat(staleClaim).isEqualTo(0);

        // 2. Claim track1 for ROLLING_BACK from ADDED
        // Follow legitimate domain transition: PENDING -> markAdding() -> ADDING -> markAdded() -> ADDED
        ExecutionTrackRecord currentTrack1 = trackRecordRepository.findById(track1.getId()).orElseThrow();
        currentTrack1.markAdding();
        currentTrack1.markAdded("yt_confirmed_item");
        trackRecordRepository.saveAndFlush(currentTrack1);
        entityManager.clear();

        int claimedRollback = trackRecordRepository.claimTrackRollingBackWithFencing(track1.getId(), execution.getId(), gen);
        assertThat(claimedRollback).isEqualTo(1);

        entityManager.clear();
        ExecutionTrackRecord reloaded1 = trackRecordRepository.findById(track1.getId()).orElseThrow();
        assertThat(reloaded1.getStatus()).isEqualTo(ExecutionTrackStatus.ROLLING_BACK);
        assertThat(reloaded1.getStartedAt()).isNotNull();
        assertThat(reloaded1.getErrorMessage()).contains("Compensating deletion in progress");
        assertThat(reloaded1.getVersion()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("Case A: Track claim commits before release obtains parent lock -> track becomes ADDING, release must fail safely")
    void testA_trackClaimCommitsBeforeReleaseObtainsParentLock() throws Exception {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AtomicLong leaseGen = new AtomicLong();
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            leaseGen.set(exec.acquireLease("worker-1", now.plusSeconds(60), now));
            executionRepository.saveAndFlush(exec);
        });
        long gen = leaseGen.get();
        entityManager.clear();

        ExecutionTrackRecord track0 = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 0).orElseThrow();
        ExecutionTrackRecord track1 = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 1).orElseThrow();

        CountDownLatch claimCommittedLatch = new CountDownLatch(1);
        AtomicInteger claimResult = new AtomicInteger(-1);
        AtomicInteger releaseResult = new AtomicInteger(-1);

        // Thread 1: Claims track0 as ADDING, commits, and signals latch
        CompletableFuture<Void> claimFuture = CompletableFuture.runAsync(() -> {
            txTemplate.executeWithoutResult(status -> {
                int res = trackRecordRepository.claimTrackAddingWithFencing(track0.getId(), execution.getId(), gen);
                claimResult.set(res);
            });
            claimCommittedLatch.countDown();
        });

        // Thread 2: Waits for track claim to commit, then attempts releaseLease
        CompletableFuture<Void> releaseFuture = CompletableFuture.runAsync(() -> {
            try {
                assertThat(claimCommittedLatch.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            txTemplate.executeWithoutResult(status -> {
                int res = executionRepository.releaseLease(
                        execution.getId(),
                        "worker-1",
                        gen,
                        MigrationExecutionStatus.QUEUED,
                        Instant.now().plusSeconds(30)
                );
                releaseResult.set(res);
            });
        });

        CompletableFuture.allOf(claimFuture, releaseFuture).join();

        // Verification of return values:
        assertThat(claimResult.get()).isEqualTo(1);
        assertThat(releaseResult.get()).isEqualTo(0);

        // Verification of persisted database state:
        entityManager.clear();
        MigrationExecution persistedExec = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(persistedExec.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
        assertThat(persistedExec.getLeaseOwner()).isEqualTo("worker-1");
        assertThat(persistedExec.getLeaseGeneration()).isEqualTo(gen);

        ExecutionTrackRecord persistedTrack0 = trackRecordRepository.findById(track0.getId()).orElseThrow();
        assertThat(persistedTrack0.getStatus()).isEqualTo(ExecutionTrackStatus.ADDING);
        assertThat(persistedTrack0.getAttemptCount()).isEqualTo(1);
        assertThat(persistedTrack0.getStartedAt()).isNotNull();

        ExecutionTrackRecord persistedTrack1 = trackRecordRepository.findById(track1.getId()).orElseThrow();
        assertThat(persistedTrack1.getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);
    }

    @Test
    @DisplayName("Case B: Release obtains parent lock before track claim -> release commits, track claim fails because ownership cleared")
    void testB_releaseObtainsParentLockBeforeTrackClaim() throws Exception {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AtomicLong leaseGen = new AtomicLong();
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            leaseGen.set(exec.acquireLease("worker-1", now.plusSeconds(60), now));
            executionRepository.saveAndFlush(exec);
        });
        long gen = leaseGen.get();
        entityManager.clear();

        ExecutionTrackRecord track0 = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 0).orElseThrow();

        CountDownLatch releaseCommittedLatch = new CountDownLatch(1);
        AtomicInteger releaseResult = new AtomicInteger(-1);
        AtomicInteger claimResult = new AtomicInteger(-1);

        // Thread 1: Releases lease with zero in-flight tracks, commits, and signals latch
        CompletableFuture<Void> releaseFuture = CompletableFuture.runAsync(() -> {
            txTemplate.executeWithoutResult(status -> {
                int res = executionRepository.releaseLease(
                        execution.getId(),
                        "worker-1",
                        gen,
                        MigrationExecutionStatus.QUEUED,
                        Instant.now().plusSeconds(30)
                );
                releaseResult.set(res);
            });
            releaseCommittedLatch.countDown();
        });

        // Thread 2: Waits for release to commit, then attempts track claim
        CompletableFuture<Void> claimFuture = CompletableFuture.runAsync(() -> {
            try {
                assertThat(releaseCommittedLatch.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            txTemplate.executeWithoutResult(status -> {
                int res = trackRecordRepository.claimTrackAddingWithFencing(track0.getId(), execution.getId(), gen);
                claimResult.set(res);
            });
        });

        CompletableFuture.allOf(releaseFuture, claimFuture).join();

        // Verification of return values:
        assertThat(releaseResult.get()).isEqualTo(1);
        assertThat(claimResult.get()).isEqualTo(0);

        // Verification of persisted database state:
        entityManager.clear();
        MigrationExecution persistedExec = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(persistedExec.getStatus()).isEqualTo(MigrationExecutionStatus.QUEUED);
        assertThat(persistedExec.getLeaseOwner()).isNull();
        assertThat(persistedExec.getLeaseUntil()).isNull();

        ExecutionTrackRecord persistedTrack0 = trackRecordRepository.findById(track0.getId()).orElseThrow();
        assertThat(persistedTrack0.getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);
        assertThat(persistedTrack0.getStartedAt()).isNull();
        assertThat(persistedTrack0.getAttemptCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Case C: Concurrent release and track claim -> mutual exclusion verified on persisted rows and return values")
    void testC_concurrentReleaseAndTrackClaimMutualExclusion() throws Exception {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AtomicLong leaseGen = new AtomicLong();
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            leaseGen.set(exec.acquireLease("worker-1", now.plusSeconds(60), now));
            executionRepository.saveAndFlush(exec);
        });
        long gen = leaseGen.get();
        entityManager.clear();

        ExecutionTrackRecord track0 = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 0).orElseThrow();

        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger claimResult = new AtomicInteger(-1);
        AtomicInteger releaseResult = new AtomicInteger(-1);

        // Thread 1: tries to claim track0 as ADDING
        CompletableFuture<Void> claimFuture = CompletableFuture.runAsync(() -> {
            try {
                startGate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            txTemplate.executeWithoutResult(status -> {
                int res = trackRecordRepository.claimTrackAddingWithFencing(track0.getId(), execution.getId(), gen);
                claimResult.set(res);
            });
        });

        // Thread 2: concurrently tries to release the lease
        CompletableFuture<Void> releaseFuture = CompletableFuture.runAsync(() -> {
            try {
                startGate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            txTemplate.executeWithoutResult(status -> {
                int res = executionRepository.releaseLease(
                        execution.getId(),
                        "worker-1",
                        gen,
                        MigrationExecutionStatus.QUEUED,
                        Instant.now().plusSeconds(30)
                );
                releaseResult.set(res);
            });
        });

        // Release both threads simultaneously
        startGate.countDown();

        CompletableFuture.allOf(claimFuture, releaseFuture).join();

        // Exactly one operation must succeed:
        boolean validSerialization = (claimResult.get() == 1 && releaseResult.get() == 0)
                || (claimResult.get() == 0 && releaseResult.get() == 1);

        assertThat(validSerialization)
                .as("Serialization invariant violated: claimResult=%d, releaseResult=%d", claimResult.get(), releaseResult.get())
                .isTrue();

        // Verify persisted parent and track states match the winning operation:
        entityManager.clear();
        MigrationExecution persistedExec = executionRepository.findById(execution.getId()).orElseThrow();
        ExecutionTrackRecord persistedTrack0 = trackRecordRepository.findById(track0.getId()).orElseThrow();

        if (claimResult.get() == 1) {
            // Claim won: execution remains RUNNING under worker-1, track is ADDING
            assertThat(persistedExec.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
            assertThat(persistedExec.getLeaseOwner()).isEqualTo("worker-1");
            assertThat(persistedTrack0.getStatus()).isEqualTo(ExecutionTrackStatus.ADDING);
        } else {
            // Release won: execution is QUEUED with null leaseOwner, track remains PENDING
            assertThat(persistedExec.getStatus()).isEqualTo(MigrationExecutionStatus.QUEUED);
            assertThat(persistedExec.getLeaseOwner()).isNull();
            assertThat(persistedTrack0.getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);
        }
    }

    @Test
    @DisplayName("Case D: Parallel track claims under same generation -> both tracks claimed concurrently without lock serialization conflicts")
    void testD_parallelTrackClaimsUnderSameGeneration() throws Exception {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AtomicLong leaseGen = new AtomicLong();
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            leaseGen.set(exec.acquireLease("worker-1", now.plusSeconds(60), now));
            executionRepository.saveAndFlush(exec);
        });
        long gen = leaseGen.get();
        entityManager.clear();

        ExecutionTrackRecord track0 = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 0).orElseThrow();
        ExecutionTrackRecord track1 = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 1).orElseThrow();

        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger claim0Result = new AtomicInteger(-1);
        AtomicInteger claim1Result = new AtomicInteger(-1);

        // Thread 1: claims track0 as ADDING
        CompletableFuture<Void> claim0Future = CompletableFuture.runAsync(() -> {
            try {
                startGate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            txTemplate.executeWithoutResult(status -> {
                int res = trackRecordRepository.claimTrackAddingWithFencing(track0.getId(), execution.getId(), gen);
                claim0Result.set(res);
            });
        });

        // Thread 2: concurrently claims track1 as ADDING under same generation
        CompletableFuture<Void> claim1Future = CompletableFuture.runAsync(() -> {
            try {
                startGate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            txTemplate.executeWithoutResult(status -> {
                int res = trackRecordRepository.claimTrackAddingWithFencing(track1.getId(), execution.getId(), gen);
                claim1Result.set(res);
            });
        });

        // Release both threads simultaneously
        startGate.countDown();

        CompletableFuture.allOf(claim0Future, claim1Future).join();

        // Both parallel track claims must succeed:
        assertThat(claim0Result.get()).isEqualTo(1);
        assertThat(claim1Result.get()).isEqualTo(1);

        // Verification of persisted database state:
        entityManager.clear();
        ExecutionTrackRecord persisted0 = trackRecordRepository.findById(track0.getId()).orElseThrow();
        ExecutionTrackRecord persisted1 = trackRecordRepository.findById(track1.getId()).orElseThrow();

        assertThat(persisted0.getStatus()).isEqualTo(ExecutionTrackStatus.ADDING);
        assertThat(persisted0.getAttemptCount()).isEqualTo(1);
        assertThat(persisted0.getStartedAt()).isNotNull();

        assertThat(persisted1.getStatus()).isEqualTo(ExecutionTrackStatus.ADDING);
        assertThat(persisted1.getAttemptCount()).isEqualTo(1);
        assertThat(persisted1.getStartedAt()).isNotNull();

        MigrationExecution persistedExec = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(persistedExec.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
        assertThat(persistedExec.getLeaseOwner()).isEqualTo("worker-1");
    }

    @Test
    @DisplayName("Regression: Stale managed entity cannot silently overwrite native SQL updates")
    void managedEntityCannotSilentlyOverwriteNativeUpdate() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AtomicLong leaseGen = new AtomicLong();
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            leaseGen.set(exec.acquireLease("worker-1", now.plusSeconds(60), now));
            executionRepository.saveAndFlush(exec);
        });
        long gen = leaseGen.get();

        ExecutionTrackRecord track0 = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 0).orElseThrow();
        Long initialVersion = track0.getVersion();

        // Native SQL updates track in database and increments version
        int updated = trackRecordRepository.claimTrackAddingWithFencing(track0.getId(), execution.getId(), gen);
        assertThat(updated).isEqualTo(1);

        // Verify version incremented in PostgreSQL
        entityManager.clear();
        ExecutionTrackRecord inDb = trackRecordRepository.findById(track0.getId()).orElseThrow();
        assertThat(inDb.getVersion()).isGreaterThan(initialVersion);

        // Attempting to save the stale managed entity (with initialVersion) must be rejected by Hibernate optimistic locking
        // Follow legitimate domain transition: PENDING -> markAdding() -> ADDING -> markAdded() -> ADDED
        track0.markAdding();
        track0.markAdded("yt_stale_overwrite");
        assertThatThrownBy(() -> {
            trackRecordRepository.saveAndFlush(track0);
        }).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("Regression: Lease expiry is evaluated against database wall-clock time after lock waits")
    void leaseExpiryEvaluatedAgainstDatabaseWallClockTimeAfterLockWait() throws Exception {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // Worker-1 acquires lease with only 1 second duration
        AtomicLong leaseGen = new AtomicLong();
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            leaseGen.set(exec.acquireLease("worker-1", now.plusSeconds(1), now));
            executionRepository.saveAndFlush(exec);
        });
        long gen = leaseGen.get();
        entityManager.clear();

        ExecutionTrackRecord track0 = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 0).orElseThrow();

        // Wait 1.2 seconds so lease elapses in wall-clock time
        Thread.sleep(1200);

        // Attempting claimTrackAddingWithFencing must fail because clock_timestamp() > lease_until
        int claimed = trackRecordRepository.claimTrackAddingWithFencing(track0.getId(), execution.getId(), gen);
        assertThat(claimed).isEqualTo(0);

        // Attempting releaseLease must also fail because clock_timestamp() > lease_until
        int released = executionRepository.releaseLease(
                execution.getId(),
                "worker-1",
                gen,
                MigrationExecutionStatus.QUEUED,
                Instant.now().plusSeconds(30)
        );
        assertThat(released).isEqualTo(0);
    }

    @Test
    @DisplayName("Deterministic clock wait: track claim blocked on parent lock fails if lease expires during lock wait")
    void trackClaimAfterParentLockWaitEvaluatesDatabaseClockTimestamp() throws Exception {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // 1. Establish valid worker lease with a short remaining lifetime (1500ms)
        Instant leaseUntil = now.plus(1500, ChronoUnit.MILLIS);
        AtomicLong leaseGen = new AtomicLong();
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            leaseGen.set(exec.acquireLease("worker-1", leaseUntil, now));
            executionRepository.saveAndFlush(exec);
        });
        long gen = leaseGen.get();
        entityManager.clear();

        ExecutionTrackRecord track0 = trackRecordRepository.findByExecutionIdAndTargetPosition(execution.getId(), 0).orElseThrow();

        CountDownLatch lockA_AcquiredLatch = new CountDownLatch(1);
        CountDownLatch releaseLockA_Latch = new CountDownLatch(1);
        AtomicInteger trackClaimResult = new AtomicInteger(-1);

        // 2. Transaction A acquires SELECT FOR UPDATE on parent execution row
        CompletableFuture<Void> threadA = CompletableFuture.runAsync(() -> {
            txTemplate.executeWithoutResult(status -> {
                entityManager.createNativeQuery(
                        "SELECT id FROM migration_executions WHERE id = :id FOR UPDATE"
                )
                .setParameter("id", execution.getId())
                .getResultList();

                lockA_AcquiredLatch.countDown();

                try {
                    boolean proceed = releaseLockA_Latch.await(10, TimeUnit.SECONDS);
                    assertThat(proceed).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        });

        try {
            // Confirm Transaction A acquired parent lock
            assertThat(lockA_AcquiredLatch.await(5, TimeUnit.SECONDS)).isTrue();

            // 3. Transaction B starts track-claim before the lease expires
            CompletableFuture<Void> threadB = CompletableFuture.runAsync(() -> {
                txTemplate.executeWithoutResult(status -> {
                    int claimed = trackRecordRepository.claimTrackAddingWithFencing(
                            track0.getId(),
                            execution.getId(),
                            gen
                    );
                    trackClaimResult.set(claimed);
                });
            });

            // 4. Confirm Transaction B is actively waiting on the PostgreSQL parent row lock
            boolean isWaitingOnDbLock = false;
            long pollDeadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < pollDeadline) {
                Number waitingLocks = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM pg_locks WHERE NOT granted AND pid <> pg_backend_pid()"
                ).getSingleResult();
                if (waitingLocks != null && waitingLocks.longValue() > 0) {
                    isWaitingOnDbLock = true;
                    break;
                }
                Thread.sleep(25);
            }
            assertThat(isWaitingOnDbLock)
                    .as("PostgreSQL pg_locks must confirm Transaction B is actively waiting on ungranted lock")
                    .isTrue();
            assertThat(threadB.isDone()).isFalse();

            // 5. Allow the lease to expire in database wall-clock time
            long sleepMillis = Duration.between(Instant.now(), leaseUntil).toMillis() + 200;
            if (sleepMillis > 0) {
                Thread.sleep(sleepMillis);
            }

            // 6. Commit transaction A
            releaseLockA_Latch.countDown();
            threadA.get(5, TimeUnit.SECONDS);

            // 7. Transaction B unblocks; confirm it returns 0 updated rows
            threadB.get(5, TimeUnit.SECONDS);
            assertThat(trackClaimResult.get()).isEqualTo(0);

            // 8. Verify the track remains PENDING and attempt_count remains unchanged
            entityManager.clear();
            ExecutionTrackRecord reloadedTrack = trackRecordRepository.findById(track0.getId()).orElseThrow();
            assertThat(reloadedTrack.getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);
            assertThat(reloadedTrack.getAttemptCount()).isEqualTo(0);
            assertThat(reloadedTrack.getStartedAt()).isNull();
        } finally {
            releaseLockA_Latch.countDown();
            threadA.cancel(true);
        }
    }

    @Test
    @DisplayName("Deterministic clock wait: lease release blocked on parent lock fails if lease expires during lock wait")
    void leaseReleaseAfterParentLockWaitEvaluatesDatabaseClockTimestamp() throws Exception {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // 1. Establish valid worker lease with a short remaining lifetime (1500ms)
        Instant leaseUntil = now.plus(1500, ChronoUnit.MILLIS);
        AtomicLong leaseGen = new AtomicLong();
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            leaseGen.set(exec.acquireLease("worker-1", leaseUntil, now));
            executionRepository.saveAndFlush(exec);
        });
        long gen = leaseGen.get();
        entityManager.clear();

        CountDownLatch lockA_AcquiredLatch = new CountDownLatch(1);
        CountDownLatch releaseLockA_Latch = new CountDownLatch(1);
        AtomicInteger releaseResult = new AtomicInteger(-1);

        // 2. Transaction A holds the parent row lock via SELECT FOR UPDATE
        CompletableFuture<Void> threadA = CompletableFuture.runAsync(() -> {
            txTemplate.executeWithoutResult(status -> {
                entityManager.createNativeQuery(
                        "SELECT id FROM migration_executions WHERE id = :id FOR UPDATE"
                )
                .setParameter("id", execution.getId())
                .getResultList();

                lockA_AcquiredLatch.countDown();

                try {
                    boolean proceed = releaseLockA_Latch.await(10, TimeUnit.SECONDS);
                    assertThat(proceed).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        });

        try {
            // Confirm Transaction A acquired parent lock
            assertThat(lockA_AcquiredLatch.await(5, TimeUnit.SECONDS)).isTrue();

            // 3. Transaction B starts releaseLease before the lease expires
            CompletableFuture<Void> threadB = CompletableFuture.runAsync(() -> {
                txTemplate.executeWithoutResult(status -> {
                    int released = executionRepository.releaseLease(
                            execution.getId(),
                            "worker-1",
                            gen,
                            MigrationExecutionStatus.QUEUED,
                            Instant.now().plusSeconds(30)
                    );
                    releaseResult.set(released);
                });
            });

            // 4. Confirm Transaction B is actively waiting on the PostgreSQL parent row lock
            boolean isWaitingOnDbLock = false;
            long pollDeadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < pollDeadline) {
                Number waitingLocks = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM pg_locks WHERE NOT granted AND pid <> pg_backend_pid()"
                ).getSingleResult();
                if (waitingLocks != null && waitingLocks.longValue() > 0) {
                    isWaitingOnDbLock = true;
                    break;
                }
                Thread.sleep(25);
            }
            assertThat(isWaitingOnDbLock)
                    .as("PostgreSQL pg_locks must confirm Transaction B is actively waiting on ungranted lock")
                    .isTrue();
            assertThat(threadB.isDone()).isFalse();

            // 5. Allow the lease to expire in database wall-clock time
            long sleepMillis = Duration.between(Instant.now(), leaseUntil).toMillis() + 200;
            if (sleepMillis > 0) {
                Thread.sleep(sleepMillis);
            }

            // 6. Commit transaction A
            releaseLockA_Latch.countDown();
            threadA.get(5, TimeUnit.SECONDS);

            // 7. Transaction B unblocks; confirm releaseLease returns zero
            threadB.get(5, TimeUnit.SECONDS);
            assertThat(releaseResult.get()).isEqualTo(0);

            // 8. Verify the expired lease was not released by the stale transaction
            entityManager.clear();
            MigrationExecution reloadedExec = executionRepository.findById(execution.getId()).orElseThrow();
            assertThat(reloadedExec.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
            assertThat(reloadedExec.getLeaseOwner()).isEqualTo("worker-1");
            assertThat(reloadedExec.getLeaseGeneration()).isEqualTo(gen);
        } finally {
            releaseLockA_Latch.countDown();
            threadA.cancel(true);
        }
    }
}
