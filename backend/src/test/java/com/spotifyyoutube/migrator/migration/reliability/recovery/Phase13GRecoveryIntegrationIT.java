package com.spotifyyoutube.migrator.migration.reliability.recovery;

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
import com.spotifyyoutube.migrator.migration.reliability.idempotency.MigrationIdempotencyRecord;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL Testcontainers integration tests verifying Phase 13G Recovery & Resume integration:
 * - Scheduler recovery of ALLOCATING_DESTINATION, RECOVERY_REQUIRED, expired RUNNING, and PARTIALLY_ROLLED_BACK
 * - Strict exclusion of terminal executions (COMPLETED, FAILED, ROLLED_BACK)
 * - Immediate fencing rejection on lease expiry (lease_until <= clock_timestamp())
 * - Durable rollback intent tracking (V14 migration), idempotency clearTerminalExpiry, and finalizeRollbackWithFencing
 * - Pre-claim status restoration during compensation
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JpaConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Phase 13G: PostgreSQL Recovery & Resume Integration Tests")
public class Phase13GRecoveryIntegrationIT {

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

    @PersistenceContext
    private EntityManager entityManager;

    private User testUser;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        testUser = userRepository.save(new User("phase13g-recovery-it@example.com"));
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
    @DisplayName("Scheduler claims interrupted ALLOCATING_DESTINATION execution with expired or null lease")
    void schedulerClaimsInterruptedAllocatingDestination() {
        MigrationExecution exec = createExecution(
                MigrationExecutionStatus.ALLOCATING_DESTINATION,
                Instant.now().minus(10, ChronoUnit.SECONDS),
                "stale-worker",
                1L,
                null
        );

        Optional<ClaimedExecution> claimed = executionRepository.claimNextEligibleExecution("worker-new", 60L);

        assertThat(claimed).isPresent();
        assertThat(claimed.get().executionId()).isEqualTo(exec.getId());
        assertThat(claimed.get().preClaimStatus()).isEqualTo(MigrationExecutionStatus.ALLOCATING_DESTINATION);
        assertThat(claimed.get().leaseGeneration()).isEqualTo(2L);
        assertThat(claimed.get().workerId()).isEqualTo("worker-new");
    }

    @Test
    @DisplayName("Scheduler claims interrupted RECOVERY_REQUIRED execution with expired lease")
    void schedulerClaimsInterruptedRecoveryRequired() {
        MigrationExecution exec = createExecution(
                MigrationExecutionStatus.RECOVERY_REQUIRED,
                Instant.now().minus(30, ChronoUnit.SECONDS),
                "crashed-worker",
                3L,
                null
        );

        Optional<ClaimedExecution> claimed = executionRepository.claimNextEligibleExecution("worker-recoverer", 60L);

        assertThat(claimed).isPresent();
        assertThat(claimed.get().executionId()).isEqualTo(exec.getId());
        assertThat(claimed.get().preClaimStatus()).isEqualTo(MigrationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(claimed.get().leaseGeneration()).isEqualTo(4L);
    }

    @Test
    @DisplayName("Scheduler claims PARTIALLY_ROLLED_BACK execution when rollback intent is active")
    void schedulerClaimsPartiallyRolledBackWithRollbackIntent() {
        MigrationExecution exec = createExecution(
                MigrationExecutionStatus.PARTIALLY_ROLLED_BACK,
                Instant.now().minus(5, ChronoUnit.SECONDS),
                "rollback-worker-old",
                5L,
                null
        );
        exec.setRollbackRequestedAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        exec.setRollbackTargetPositions("0,1");
        executionRepository.saveAndFlush(exec);

        Optional<ClaimedExecution> claimed = executionRepository.claimNextEligibleExecution("worker-rollback-resume", 60L);

        assertThat(claimed).isPresent();
        assertThat(claimed.get().executionId()).isEqualTo(exec.getId());
        assertThat(claimed.get().preClaimStatus()).isEqualTo(MigrationExecutionStatus.PARTIALLY_ROLLED_BACK);
    }

    @Test
    @DisplayName("Scheduler strictly excludes terminal executions (COMPLETED, FAILED, ROLLED_BACK)")
    void schedulerExcludesTerminalExecutions() {
        createExecution(MigrationExecutionStatus.COMPLETED, null, null, null, null);
        createExecution(MigrationExecutionStatus.FAILED, null, null, null, null);
        createExecution(MigrationExecutionStatus.ROLLED_BACK, null, null, null, null);

        Optional<ClaimedExecution> claimed = executionRepository.claimNextEligibleExecution("worker-1", 60L);
        assertThat(claimed).isEmpty();
    }

    @Test
    @DisplayName("Immediate fencing rejects track status update when lease has expired")
    void immediateFencingRejectsTrackStatusUpdateOnLeaseExpiry() {
        Instant expiredLease = Instant.now().minus(5, ChronoUnit.SECONDS);
        MigrationExecution exec = createExecution(
                MigrationExecutionStatus.RUNNING,
                expiredLease,
                "slow-worker",
                10L,
                null
        );

        ExecutionTrackRecord track = exec.getTrackRecords().get(0);
        track.markAdding();
        trackRecordRepository.saveAndFlush(track);

        // Slow worker attempts update with matching generation (10L) but lease has expired
        int rows = trackRecordRepository.updateTrackStatusWithFencing(
                track.getId(),
                exec.getId(),
                10L,
                ExecutionTrackStatus.ADDED,
                "yt_item_123",
                null,
                Instant.now()
        );

        assertThat(rows).isEqualTo(0);
    }

    @Test
    @DisplayName("Immediate fencing rejects retry scheduling when lease has expired")
    void immediateFencingRejectsRetrySchedulingOnLeaseExpiry() {
        Instant expiredLease = Instant.now().minus(5, ChronoUnit.SECONDS);
        MigrationExecution exec = createExecution(
                MigrationExecutionStatus.RUNNING,
                expiredLease,
                "slow-worker",
                10L,
                null
        );

        ExecutionTrackRecord track = exec.getTrackRecords().get(0);
        track.markAdding();
        trackRecordRepository.saveAndFlush(track);

        int rows = trackRecordRepository.scheduleTrackRetryWithFencing(
                track.getId(),
                exec.getId(),
                10L,
                Instant.now().plus(30, ChronoUnit.SECONDS),
                "TRANSIENT_SERVER_ERROR",
                1000L
        );

        assertThat(rows).isEqualTo(0);
    }

    @Test
    @DisplayName("Immediate fencing rejects destination playlist confirmation when lease has expired")
    void immediateFencingRejectsDestinationConfirmationOnLeaseExpiry() {
        Instant expiredLease = Instant.now().minus(5, ChronoUnit.SECONDS);
        MigrationExecution exec = createExecution(
                MigrationExecutionStatus.ALLOCATING_DESTINATION,
                expiredLease,
                "slow-worker",
                10L,
                null
        );

        int rows = executionRepository.confirmDestinationPlaylistWithFencing(
                exec.getId(),
                "PL_confirmed",
                "https://youtube.com/playlist?list=PL_confirmed",
                "RUNNING",
                "slow-worker",
                10L
        );

        assertThat(rows).isEqualTo(0);
    }

    @Test
    @DisplayName("Durable rollback intent lifecycle: claim, idempotency retention clear, and finalization")
    void durableRollbackIntentLifecycle() {
        MigrationExecution exec = createExecution(
                MigrationExecutionStatus.COMPLETED,
                null,
                null,
                2L,
                null
        );

        // Pre-create an idempotency record with terminal expiry
        MigrationIdempotencyRecord idemp = new MigrationIdempotencyRecord(
                testUser.getId(),
                "test-idemp-key-1",
                "req-fp-1",
                exec.getId(),
                200,
                "{}",
                Instant.now(),
                Instant.now().plus(72, ChronoUnit.HOURS)
        );
        idempotencyRepository.saveAndFlush(idemp);

        // Step 1: Claim for rollback with intent
        long expectedVersion = exec.getVersion() != null ? exec.getVersion() : 0L;
        int claimed = executionRepository.claimExecutionForRollbackWithIntent(
                exec.getId(),
                List.of("COMPLETED", "PARTIALLY_COMPLETED"),
                expectedVersion,
                "rollback-worker-1",
                "0",
                60L
        );
        assertThat(claimed).isEqualTo(1);

        // Verify idempotency terminal retention clear
        int cleared = idempotencyRepository.clearTerminalExpiry(exec.getId());
        assertThat(cleared).isEqualTo(1);

        MigrationIdempotencyRecord refreshedIdemp = idempotencyRepository.findById(idemp.getId()).orElseThrow();
        assertThat(refreshedIdemp.getExpiresAt()).isNull();

        MigrationExecution inProgress = executionRepository.findById(exec.getId()).orElseThrow();
        assertThat(inProgress.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
        assertThat(inProgress.getRollbackRequestedAt()).isNotNull();
        assertThat(inProgress.getRollbackTargetPositions()).isEqualTo("0");
        assertThat(inProgress.getLeaseOwner()).isEqualTo("rollback-worker-1");
        assertThat(inProgress.getLeaseGeneration()).isEqualTo(3L);

        // Step 2: Finalize rollback with fencing
        int finalized = executionRepository.finalizeRollbackWithFencing(
                exec.getId(),
                "ROLLED_BACK",
                "rollback-worker-1",
                3L
        );
        assertThat(finalized).isEqualTo(1);

        MigrationExecution finished = executionRepository.findById(exec.getId()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(MigrationExecutionStatus.ROLLED_BACK);
        assertThat(finished.getRollbackRequestedAt()).isNull();
        assertThat(finished.getRollbackTargetPositions()).isNull();
        assertThat(finished.getRollbackCompletedAt()).isNotNull();
        assertThat(finished.getLeaseOwner()).isNull();
        assertThat(finished.getLeaseUntil()).isNull();
    }

    @Test
    @DisplayName("Compensate claim rejection restores pre-claim status accurately")
    void compensateClaimRejectionRestoresPreClaimStatus() {
        MigrationExecution exec = createExecution(
                MigrationExecutionStatus.ALLOCATING_DESTINATION,
                null,
                null,
                1L,
                null
        );

        Optional<ClaimedExecution> claimed = executionRepository.claimNextEligibleExecution("worker-sat", 60L);
        assertThat(claimed).isPresent();
        assertThat(claimed.get().preClaimStatus()).isEqualTo(MigrationExecutionStatus.ALLOCATING_DESTINATION);

        // Compensate dispatch rejection restoring preClaimStatus
        int comp = executionRepository.compensateClaimRejection(
                claimed.get().executionId(),
                claimed.get().workerId(),
                claimed.get().leaseGeneration(),
                Instant.now().plus(15, ChronoUnit.SECONDS),
                claimed.get().preClaimStatus()
        );
        assertThat(comp).isEqualTo(1);

        MigrationExecution reverted = executionRepository.findById(exec.getId()).orElseThrow();
        assertThat(reverted.getStatus()).isEqualTo(MigrationExecutionStatus.ALLOCATING_DESTINATION);
        assertThat(reverted.getLeaseOwner()).isNull();
        assertThat(reverted.getLeaseUntil()).isNull();
        assertThat(reverted.getNextAttemptAt()).isNotNull();
    }
}
