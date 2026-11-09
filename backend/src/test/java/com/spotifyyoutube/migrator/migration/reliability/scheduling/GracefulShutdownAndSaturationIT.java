package com.spotifyyoutube.migrator.migration.reliability.scheduling;

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
 * PostgreSQL database integration tests verifying executor saturation compensation,
 * rejection fencing, and scheduler graceful recovery (Phase 13D-F).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JpaConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Phase 13D-F: PostgreSQL Graceful Shutdown & Saturation Compensation Integration Test")
public class GracefulShutdownAndSaturationIT {

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

        testUser = userRepository.save(new User("shutdown-it@example.com"));

        testSession = new ReviewSession(
                testUser,
                "sp_playlist_shutdown_it",
                "Shutdown Playlist",
                UUID.randomUUID()
        );
        testSession = reviewSessionRepository.save(testSession);

        ApprovedTrack track0 = new ApprovedTrack(
                0, "sp_t0", "Song Zero", "Artist A",
                "yt_t0", "Song Zero (Video)", "Channel A",
                Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 0
        );

        ApprovedMigrationContext context = new ApprovedMigrationContext(
                testSession.getId(), UUID.randomUUID(), testUser.getId(),
                "sp_playlist_shutdown_it", "Shutdown Playlist", "Target Playlist",
                "Description", false, List.of(track0),
                1, 1, 0, Instant.now()
        );

        execution = new MigrationExecution(
                testUser, testSession, context,
                "PL_shutdown_123", "https://music.youtube.com/playlist?list=PL_shutdown_123",
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
    @DisplayName("compensateClaimRejection resets execution to QUEUED with backoff next_attempt_at")
    void compensateClaimRejectionSuccess() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // Claim execution
        Optional<ClaimedExecution> claim = executionRepository.claimNextEligibleExecution("worker-node-1", 60L);
        assertThat(claim).isPresent();

        Instant backoffTime = Instant.now().plusSeconds(15);

        // Compensate dispatch rejection
        int compensated = txTemplate.execute(status ->
                executionRepository.compensateClaimRejection(
                        execution.getId(), "worker-node-1", claim.get().leaseGeneration(), backoffTime
                )
        );

        assertThat(compensated).isEqualTo(1);

        MigrationExecution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MigrationExecutionStatus.QUEUED);
        assertThat(reloaded.getLeaseOwner()).isNull();
        assertThat(reloaded.getLeaseUntil()).isNull();
        assertThat(reloaded.getNextAttemptAt()).isNotNull();

        // Immediate subsequent claim is excluded due to future next_attempt_at
        Optional<ClaimedExecution> secondClaim = executionRepository.claimNextEligibleExecution("worker-node-2", 60L);
        assertThat(secondClaim).isEmpty();
    }

    @Test
    @DisplayName("compensateClaimRejection rejects compensation if fencing generation does not match")
    void compensateClaimRejectionRejectsStaleGeneration() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        Optional<ClaimedExecution> claim = executionRepository.claimNextEligibleExecution("worker-node-1", 60L);
        assertThat(claim).isPresent();

        int compensated = txTemplate.execute(status ->
                executionRepository.compensateClaimRejection(
                        execution.getId(), "worker-node-1", 999L, Instant.now().plusSeconds(15)
                )
        );

        assertThat(compensated).isEqualTo(0);
    }

    @Test
    @DisplayName("compensateClaimRejection rejects compensation if worker ID does not match")
    void compensateClaimRejectionRejectsWrongWorker() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        Optional<ClaimedExecution> claim = executionRepository.claimNextEligibleExecution("worker-node-1", 60L);
        assertThat(claim).isPresent();

        int compensated = txTemplate.execute(status ->
                executionRepository.compensateClaimRejection(
                        execution.getId(), "worker-impostor", claim.get().leaseGeneration(), Instant.now().plusSeconds(15)
                )
        );

        assertThat(compensated).isEqualTo(0);
    }
}
