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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL database integration tests verifying lease heartbeat renewal, wall-clock extension,
 * generation preservation, and stale worker rejection (Phase 13D-D).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JpaConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Phase 13D-D: PostgreSQL Durable Lease Heartbeat & Renewal Integration Test")
public class DurableHeartbeatAndRenewalIT {

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

        testUser = userRepository.save(new User("heartbeat-it@example.com"));

        testSession = new ReviewSession(
                testUser,
                "sp_playlist_heartbeat_it",
                "Heartbeat Playlist",
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
                "sp_playlist_heartbeat_it", "Heartbeat Playlist", "Target Playlist",
                "Description", false, List.of(track0),
                1, 1, 0, Instant.now()
        );

        execution = new MigrationExecution(
                testUser, testSession, context,
                "PL_heartbeat_123", "https://music.youtube.com/playlist?list=PL_heartbeat_123",
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
    @DisplayName("renewLeaseHeartbeat extends lease_until and updates heartbeat_at using PostgreSQL clock")
    void renewLeaseHeartbeatSuccess() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        Instant initialLeaseUntil = Instant.now().plusSeconds(30);
        Instant initialHeartbeat = Instant.now().minusSeconds(10);

        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            exec.acquireLease("worker-1", initialLeaseUntil, initialHeartbeat);
            executionRepository.saveAndFlush(exec);
        });

        int renewed = txTemplate.execute(status ->
                executionRepository.renewLeaseHeartbeat(execution.getId(), "worker-1", 1L, 120L)
        );

        assertThat(renewed).isEqualTo(1);

        MigrationExecution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getLeaseOwner()).isEqualTo("worker-1");
        assertThat(reloaded.getLeaseGeneration()).isEqualTo(1L);
        assertThat(reloaded.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
        assertThat(reloaded.getHeartbeatAt()).isAfter(initialHeartbeat);
        assertThat(reloaded.getLeaseUntil()).isAfter(initialLeaseUntil);
    }

    @Test
    @DisplayName("renewLeaseHeartbeat preserves monotonic lease generation without incrementing")
    void renewLeaseHeartbeatPreservesGeneration() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            exec.acquireLease("worker-1", Instant.now().plusSeconds(60), Instant.now());
            executionRepository.saveAndFlush(exec);
        });

        txTemplate.execute(status ->
                executionRepository.renewLeaseHeartbeat(execution.getId(), "worker-1", 1L, 60L)
        );

        MigrationExecution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getLeaseGeneration()).isEqualTo(1L);
    }

    @Test
    @DisplayName("renewLeaseHeartbeat rejects wrong worker ID")
    void renewLeaseRejectsWrongWorker() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            exec.acquireLease("worker-1", Instant.now().plusSeconds(60), Instant.now());
            executionRepository.saveAndFlush(exec);
        });

        int renewed = txTemplate.execute(status ->
                executionRepository.renewLeaseHeartbeat(execution.getId(), "worker-impostor", 1L, 60L)
        );

        assertThat(renewed).isEqualTo(0);
    }

    @Test
    @DisplayName("renewLeaseHeartbeat rejects stale lease generation")
    void renewLeaseRejectsStaleGeneration() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // 1. Worker-1 acquires initial lease with generation 1
        AtomicReference<MigrationExecution> staleWorker1Ref = new AtomicReference<>();
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            exec.acquireLease("worker-1", Instant.now().plusSeconds(60), Instant.now());
            MigrationExecution saved = executionRepository.saveAndFlush(exec);
            staleWorker1Ref.set(saved);
        });

        // 2. Current lease owner with valid generation 1 successfully renews heartbeat
        int renewed = txTemplate.execute(status ->
                executionRepository.renewLeaseHeartbeat(execution.getId(), "worker-1", 1L, 60L)
        );
        assertThat(renewed).isEqualTo(1);

        // 3. Simulate lease expiration so worker-2 can legitimately claim takeover
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            exec.setLeaseUntil(Instant.now().minusSeconds(5));
            executionRepository.saveAndFlush(exec);
        });

        // Worker-2 claims takeover via atomic PostgreSQL FOR UPDATE SKIP LOCKED
        // This monotonically increments lease_generation (to 2L) and version (version + 1)
        txTemplate.executeWithoutResult(status -> {
            executionRepository.claimNextEligibleExecution("worker-2", 60L);
        });

        // 4. Stale worker-1 attempts heartbeat renewal with its stale generation 1 -> rejected
        int staleRenewed = txTemplate.execute(status ->
                executionRepository.renewLeaseHeartbeat(execution.getId(), "worker-1", 1L, 60L)
        );
        assertThat(staleRenewed).as("Stale lease generation 1 must be rejected").isEqualTo(0);

        // 5. Stale worker cannot modify newer owner's lease in database
        MigrationExecution reloaded = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getLeaseOwner()).isEqualTo("worker-2");
        assertThat(reloaded.getLeaseGeneration()).isEqualTo(2L);

        // 6. Optimistic locking remains effective: stale worker holding outdated entity version is rejected
        MigrationExecution staleWorker1Exec = staleWorker1Ref.get();
        staleWorker1Exec.setLeaseUntil(Instant.now().plusSeconds(120));
        assertThatThrownBy(() -> {
            txTemplate.executeWithoutResult(status -> {
                executionRepository.saveAndFlush(staleWorker1Exec);
            });
        }).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("renewLeaseHeartbeat cannot revive an expired lease")
    void renewLeaseCannotReviveExpiredLease() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            // Simulate already-expired lease
            exec.acquireLease("worker-1", Instant.now().minusSeconds(10), Instant.now().minusSeconds(20));
            executionRepository.saveAndFlush(exec);
        });

        int renewed = txTemplate.execute(status ->
                executionRepository.renewLeaseHeartbeat(execution.getId(), "worker-1", 1L, 60L)
        );

        assertThat(renewed).isEqualTo(0);
    }

    @Test
    @DisplayName("renewLeaseHeartbeat rejects terminal executions")
    void renewLeaseRejectsTerminalExecutions() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            exec.acquireLease("worker-1", Instant.now().plusSeconds(60), Instant.now());
            exec.setStatus(MigrationExecutionStatus.COMPLETED);
            executionRepository.saveAndFlush(exec);
        });

        int renewed = txTemplate.execute(status ->
                executionRepository.renewLeaseHeartbeat(execution.getId(), "worker-1", 1L, 60L)
        );

        assertThat(renewed).isEqualTo(0);
    }

    @Test
    @DisplayName("Old worker cannot renew heartbeat after new worker claims takeover")
    void oldWorkerCannotRenewAfterTakeover() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // Step 1: worker-1 acquires lease (generation 1) but lets it expire
        txTemplate.executeWithoutResult(status -> {
            MigrationExecution exec = executionRepository.findById(execution.getId()).orElseThrow();
            exec.acquireLease("worker-1", Instant.now().minusSeconds(5), Instant.now().minusSeconds(15));
            executionRepository.saveAndFlush(exec);
        });

        // Step 2: worker-2 claims takeover via claimNextEligibleExecution
        txTemplate.executeWithoutResult(status -> {
            executionRepository.claimNextEligibleExecution("worker-2", 60L);
        });

        MigrationExecution takenOver = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(takenOver.getLeaseOwner()).isEqualTo("worker-2");
        assertThat(takenOver.getLeaseGeneration()).isEqualTo(2L);

        // Step 3: worker-1 attempts heartbeat renewal with stale generation 1
        int renewedByStaleWorker = txTemplate.execute(status ->
                executionRepository.renewLeaseHeartbeat(execution.getId(), "worker-1", 1L, 60L)
        );

        assertThat(renewedByStaleWorker).isEqualTo(0);
    }
}
