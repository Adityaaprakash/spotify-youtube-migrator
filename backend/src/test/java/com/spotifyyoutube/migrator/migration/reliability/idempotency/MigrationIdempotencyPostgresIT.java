package com.spotifyyoutube.migrator.migration.reliability.idempotency;

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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
 * PostgreSQL Testcontainers integration tests verifying Phase 13E API idempotency persistence,
 * concurrency safety, composite unique constraints, terminal-only retention, and database-clock cleanup.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JpaConfig.class, DefaultMigrationIdempotencyService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Phase 13E: API Idempotency PostgreSQL Integration Tests")
public class MigrationIdempotencyPostgresIT {

    @Autowired
    private MigrationIdempotencyRepository idempotencyRepository;

    @Autowired
    private MigrationIdempotencyService idempotencyService;

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

    private User testUser1;
    private User testUser2;
    private MigrationExecution execution1;
    private MigrationExecution execution2;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        testUser1 = userRepository.save(new User("idempotency-user-1@example.com"));
        testUser2 = userRepository.save(new User("idempotency-user-2@example.com"));

        execution1 = createExecution(testUser1, MigrationExecutionStatus.QUEUED);
        execution2 = createExecution(testUser2, MigrationExecutionStatus.QUEUED);
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

    private MigrationExecution createExecution(User user, MigrationExecutionStatus status) {
        ReviewSession session = reviewSessionRepository.save(new ReviewSession(
                user,
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
                session.getId(), UUID.randomUUID(), user.getId(),
                "sp_src_" + UUID.randomUUID(), "Source Title", "Target Playlist",
                "Description", false, List.of(track),
                1, 1, 0, Instant.now()
        );

        MigrationExecution exec = new MigrationExecution(
                user, session, context,
                "PL_" + UUID.randomUUID(), "https://music.youtube.com/playlist?list=PL_" + UUID.randomUUID(),
                status
        );
        return executionRepository.save(exec);
    }

    @Test
    @DisplayName("Concurrent identical submissions with same key create exactly one record and replay winner")
    void concurrentIdenticalSubmissions_createSingleRecordAndReplayWinner() throws Exception {
        String key = "concurrent-key-identical-001";
        String fingerprint = "v1:identicalCanonicalFingerprint999";
        int threadCount = 8;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);

        List<Future<MigrationIdempotencyRecord>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startGate.await();
                try {
                    return idempotencyService.recordSubmission(
                            testUser1.getId(), key, fingerprint, execution1.getId(), 202,
                            "{\"executionId\":\"" + execution1.getId() + "\",\"status\":\"QUEUED\"}"
                    );
                } finally {
                    endGate.countDown();
                }
            }));
        }

        startGate.countDown();
        assertThat(endGate.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Exactly 1 record persisted in database
        List<MigrationIdempotencyRecord> allRecords = idempotencyRepository.findAll();
        assertThat(allRecords).hasSize(1);

        MigrationIdempotencyRecord persisted = allRecords.get(0);
        assertThat(persisted.getUserId()).isEqualTo(testUser1.getId());
        assertThat(persisted.getIdempotencyKey()).isEqualTo(key);
        assertThat(persisted.getExecutionId()).isEqualTo(execution1.getId());
        assertThat(persisted.getExpiresAt()).isNull(); // Active execution: expires_at must be NULL

        // All 8 threads must receive the winning record with matching executionId
        for (Future<MigrationIdempotencyRecord> future : futures) {
            MigrationIdempotencyRecord record = future.get();
            assertThat(record.getExecutionId()).isEqualTo(execution1.getId());
            assertThat(record.getResponseStatus()).isEqualTo(202);
        }
    }

    @Test
    @DisplayName("Concurrent same-key submissions with different fingerprints: one wins, other receives 409")
    void concurrentMismatchedSubmissions_oneWinsOtherReceivesConflict() throws Exception {
        String key = "concurrent-key-mismatch-002";
        String fingerprintA = "v1:fingerprintPayloadA";
        String fingerprintB = "v1:fingerprintPayloadB";

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);

        Callable<String> taskA = () -> {
            startGate.await();
            try {
                idempotencyService.recordSubmission(
                        testUser1.getId(), key, fingerprintA, execution1.getId(), 202, "{\"version\":\"A\"}"
                );
                return "SUCCESS_A";
            } catch (IdempotencyFingerprintMismatchException ex) {
                return "CONFLICT_A";
            }
        };

        Callable<String> taskB = () -> {
            startGate.await();
            try {
                idempotencyService.recordSubmission(
                        testUser1.getId(), key, fingerprintB, execution1.getId(), 202, "{\"version\":\"B\"}"
                );
                return "SUCCESS_B";
            } catch (IdempotencyFingerprintMismatchException ex) {
                return "CONFLICT_B";
            }
        };

        Future<String> futureA = executor.submit(taskA);
        Future<String> futureB = executor.submit(taskB);

        startGate.countDown();
        String resultA = futureA.get(5, TimeUnit.SECONDS);
        String resultB = futureB.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one succeeded, exactly one received CONFLICT (409)
        boolean aWon = "SUCCESS_A".equals(resultA) && "CONFLICT_B".equals(resultB);
        boolean bWon = "SUCCESS_B".equals(resultB) && "CONFLICT_A".equals(resultA);
        assertThat(aWon || bWon).isTrue();

        // Database holds exactly one record
        List<MigrationIdempotencyRecord> all = idempotencyRepository.findAll();
        assertThat(all).hasSize(1);
    }

    @Test
    @DisplayName("Same idempotency key under different users does not collide (user-scoped uniqueness)")
    void sameKey_underDifferentUsers_doesNotCollide() {
        String sharedKey = "shared-client-key-abc";
        String fp = "v1:commonFingerprint";

        MigrationIdempotencyRecord recordUser1 = idempotencyService.recordSubmission(
                testUser1.getId(), sharedKey, fp, execution1.getId(), 202, "{\"user\":1}"
        );
        MigrationIdempotencyRecord recordUser2 = idempotencyService.recordSubmission(
                testUser2.getId(), sharedKey, fp, execution2.getId(), 202, "{\"user\":2}"
        );

        assertThat(recordUser1.getId()).isNotEqualTo(recordUser2.getId());
        assertThat(recordUser1.getUserId()).isEqualTo(testUser1.getId());
        assertThat(recordUser2.getUserId()).isEqualTo(testUser2.getId());
        assertThat(recordUser1.getIdempotencyKey()).isEqualTo(recordUser2.getIdempotencyKey());

        assertThat(idempotencyRepository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("Transaction rollback does not leave orphaned idempotency records")
    void transactionRollback_doesNotLeaveOrphanedRecords() {
        String key = "rollback-test-key";
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            idempotencyRepository.save(MigrationIdempotencyRecord.createActive(
                    testUser1.getId(), key, "v1:hash", execution1.getId(), 202, "{}"
            ));
            throw new RuntimeException("Simulated mid-transaction failure");
        })).isInstanceOf(RuntimeException.class);

        Optional<MigrationIdempotencyRecord> found =
                idempotencyRepository.findByUserIdAndIdempotencyKey(testUser1.getId(), key);
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("Terminal-only retention: active is null, terminal sets +72h, repeated transition does not extend")
    void terminalOnlyRetention_lifecycleAndInvariants() {
        String key = "lifecycle-key-101";
        MigrationIdempotencyRecord record = idempotencyService.recordSubmission(
                testUser1.getId(), key, "v1:fp1", execution1.getId(), 202, "{}"
        );

        // 1. Initial active status -> expires_at must be NULL
        assertThat(record.getExpiresAt()).isNull();

        // 2. Terminal transition sets expires_at to terminalTransitionTime + 72h
        Instant terminalTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        int updated = idempotencyService.markExecutionTerminal(execution1.getId(), terminalTime);
        assertThat(updated).isEqualTo(1);

        MigrationIdempotencyRecord updatedRecord =
                idempotencyRepository.findByUserIdAndIdempotencyKey(testUser1.getId(), key).orElseThrow();
        Instant expectedExpiry = terminalTime.plus(Duration.ofHours(72));
        assertThat(updatedRecord.getExpiresAt()).isNotNull();
        assertThat(ChronoUnit.SECONDS.between(updatedRecord.getExpiresAt(), expectedExpiry)).isZero();

        // 3. Repeated terminal transition with later timestamp does not extend expiry
        Instant laterTime = terminalTime.plus(Duration.ofHours(5));
        int repeatedUpdate = idempotencyService.markExecutionTerminal(execution1.getId(), laterTime);
        assertThat(repeatedUpdate).isEqualTo(0);

        MigrationIdempotencyRecord recordAfterRepeated =
                idempotencyRepository.findByUserIdAndIdempotencyKey(testUser1.getId(), key).orElseThrow();
        assertThat(ChronoUnit.SECONDS.between(recordAfterRepeated.getExpiresAt(), expectedExpiry)).isZero();
    }

    @Test
    @DisplayName("Database-clock cleanup deletes only expired records and preserves active and unexpired records")
    void databaseClockCleanup_deletesOnlyExpiredTerminalRecords() {
        // Record 1: Active execution (expires_at is NULL) -> MUST NOT BE DELETED
        idempotencyRepository.save(MigrationIdempotencyRecord.createActive(
                testUser1.getId(), "active-key", "v1:fp", execution1.getId(), 202, "{}"
        ));

        // Record 2: Terminal unexpired (expires in 24 hours) -> MUST NOT BE DELETED
        MigrationIdempotencyRecord unexpired = MigrationIdempotencyRecord.createActive(
                testUser1.getId(), "unexpired-key", "v1:fp", execution1.getId(), 202, "{}"
        );
        // expires_at = now - 10h + 72h = now + 62h
        unexpired.markTerminal(Instant.now().minus(Duration.ofHours(10)));
        idempotencyRepository.save(unexpired);

        // Record 3: Terminal expired (expired 5 hours ago) -> MUST BE DELETED
        MigrationIdempotencyRecord expired = MigrationIdempotencyRecord.createActive(
                testUser1.getId(), "expired-key", "v1:fp", execution1.getId(), 202, "{}"
        );
        // expires_at = now - 80h + 72h = now - 8h (< now)
        expired.markTerminal(Instant.now().minus(Duration.ofHours(80)));
        idempotencyRepository.save(expired);

        assertThat(idempotencyRepository.findAll()).hasSize(3);

        // Run sweeper using authoritative database clock time
        int deleted = idempotencyService.cleanupExpiredRecords();
        assertThat(deleted).isEqualTo(1);

        List<MigrationIdempotencyRecord> remaining = idempotencyRepository.findAll();
        assertThat(remaining).hasSize(2);
        assertThat(remaining).extracting(MigrationIdempotencyRecord::getIdempotencyKey)
                .containsExactlyInAnyOrder("active-key", "unexpired-key");
    }
}
