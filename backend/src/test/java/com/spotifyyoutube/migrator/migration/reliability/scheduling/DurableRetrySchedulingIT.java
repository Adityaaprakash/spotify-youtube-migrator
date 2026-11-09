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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL database integration tests verifying durable retry scheduling, earliest due track discovery,
 * future retry exclusion, and aggregate next_attempt_at coordination (Phase 13D-E).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JpaConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Phase 13D-E: PostgreSQL Durable Retry Scheduling Integration Test")
public class DurableRetrySchedulingIT {

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

        testUser = userRepository.save(new User("retry-scheduling-it@example.com"));

        testSession = new ReviewSession(
                testUser,
                "sp_playlist_retry_it",
                "Retry Playlist",
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
                "sp_playlist_retry_it", "Retry Playlist", "Target Playlist",
                "Description", false, List.of(track0, track1),
                2, 2, 0, Instant.now()
        );

        execution = new MigrationExecution(
                testUser, testSession, context,
                "PL_retry_123", "https://music.youtube.com/playlist?list=PL_retry_123",
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
    @DisplayName("scheduleTrackRetryWithFencing preserves PENDING status and records retry metadata")
    void scheduleTrackRetryPersistsMetadata() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            exec.acquireLease("worker-1", Instant.now().plusSeconds(60), Instant.now());
            executionRepository.saveAndFlush(exec);
        });

        ExecutionTrackRecord track0 = trackRecordRepository
                .findByExecutionIdAndTargetPosition(execution.getId(), 0).orElseThrow();

        // Atomically claim track to ADDING
        int claimed = trackRecordRepository.claimTrackAddingWithFencing(track0.getId(), execution.getId(), 1L);
        assertThat(claimed).isEqualTo(1);

        // Schedule retry with fencing
        Instant retryTime = Instant.now().plusSeconds(45);
        int scheduled = trackRecordRepository.scheduleTrackRetryWithFencing(
                track0.getId(), execution.getId(), 1L, retryTime, "RATE_LIMITED", 45000L
        );
        assertThat(scheduled).isEqualTo(1);

        ExecutionTrackRecord reloaded = trackRecordRepository.findById(track0.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);
        assertThat(reloaded.getNextAttemptAt()).isNotNull();
        assertThat(reloaded.getLastFailureClassification()).isEqualTo("RATE_LIMITED");
        assertThat(reloaded.getRetryBackoffMs()).isEqualTo(45000L);
    }

    @Test
    @DisplayName("findEarliestDeferredRetry returns earliest future timestamp across deferred tracks")
    void findEarliestDeferredRetryReturnsMinimum() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            exec.acquireLease("worker-1", Instant.now().plusSeconds(60), Instant.now());
            executionRepository.saveAndFlush(exec);
        });

        ExecutionTrackRecord track0 = trackRecordRepository
                .findByExecutionIdAndTargetPosition(execution.getId(), 0).orElseThrow();
        ExecutionTrackRecord track1 = trackRecordRepository
                .findByExecutionIdAndTargetPosition(execution.getId(), 1).orElseThrow();

        Instant t0Retry = Instant.now().plusSeconds(30);
        Instant t1Retry = Instant.now().plusSeconds(60);

        trackRecordRepository.claimTrackAddingWithFencing(track0.getId(), execution.getId(), 1L);
        trackRecordRepository.scheduleTrackRetryWithFencing(
                track0.getId(), execution.getId(), 1L, t0Retry, "RATE_LIMITED", 30000L
        );

        trackRecordRepository.claimTrackAddingWithFencing(track1.getId(), execution.getId(), 1L);
        trackRecordRepository.scheduleTrackRetryWithFencing(
                track1.getId(), execution.getId(), 1L, t1Retry, "RATE_LIMITED", 60000L
        );

        Optional<Instant> earliest = trackRecordRepository.findEarliestDeferredRetry(execution.getId());
        assertThat(earliest).isPresent();
        assertThat(earliest.get()).isBefore(t1Retry);
    }

    @Test
    @DisplayName("findDuePendingTracks includes due tracks and excludes future retries")
    void findDuePendingTracksFiltersByPostgresClock() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            exec.acquireLease("worker-1", Instant.now().plusSeconds(60), Instant.now());
            executionRepository.saveAndFlush(exec);
        });

        ExecutionTrackRecord track0 = trackRecordRepository
                .findByExecutionIdAndTargetPosition(execution.getId(), 0).orElseThrow();
        ExecutionTrackRecord track1 = trackRecordRepository
                .findByExecutionIdAndTargetPosition(execution.getId(), 1).orElseThrow();

        // Track 0 is deferred to future (+60s)
        trackRecordRepository.claimTrackAddingWithFencing(track0.getId(), execution.getId(), 1L);
        trackRecordRepository.scheduleTrackRetryWithFencing(
                track0.getId(), execution.getId(), 1L, Instant.now().plusSeconds(60), "RATE_LIMITED", 60000L
        );

        // Track 1 has nextAttemptAt = null (immediately due)
        List<ExecutionTrackRecord> dueTracks = trackRecordRepository.findDuePendingTracks(execution.getId());

        assertThat(dueTracks).hasSize(1);
        assertThat(dueTracks.get(0).getId()).isEqualTo(track1.getId());
    }

    @Test
    @DisplayName("releaseLease resets execution to QUEUED with next_attempt_at and enables future claim")
    void releaseLeaseToQueuedWithNextAttemptAt() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            exec.acquireLease("worker-1", Instant.now().plusSeconds(60), Instant.now());
            executionRepository.saveAndFlush(exec);
        });

        Instant nextAttempt = Instant.now().plusSeconds(30);

        int released = txTemplate.execute(status ->
                executionRepository.releaseLease(
                        execution.getId(), "worker-1", 1L, MigrationExecutionStatus.QUEUED, nextAttempt
                )
        );

        assertThat(released).isEqualTo(1);

        MigrationExecution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MigrationExecutionStatus.QUEUED);
        assertThat(reloaded.getLeaseOwner()).isNull();
        assertThat(reloaded.getLeaseUntil()).isNull();
        assertThat(reloaded.getNextAttemptAt()).isNotNull();

        // While next_attempt_at is in future, scheduler claim query excludes it
        Optional<ClaimedExecution> prematureClaim = executionRepository.claimNextEligibleExecution("worker-2", 60L);
        assertThat(prematureClaim).isEmpty();
    }
}
