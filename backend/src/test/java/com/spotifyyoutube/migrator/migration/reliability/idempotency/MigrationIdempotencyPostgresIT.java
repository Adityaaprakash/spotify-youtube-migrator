package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import com.spotifyyoutube.migrator.common.config.JpaConfig;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
@Import({JpaConfig.class, DefaultMigrationIdempotencyService.class, IdempotencyClaimHeartbeatService.class})
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

    @Test
    @DisplayName("PostgreSQL concurrency: 8 simultaneous acquireClaim calls result in exactly 1 ACQUIRED and 7 PENDING")
    void acquireClaim_concurrentContenders_singleWinnerAcquiresPendingClaim() throws Exception {
        String key = "claim-postgres-race-001";
        String fp = "v1:claimRaceFingerprint";
        int threadCount = 8;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);

        List<Future<ClaimResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startGate.await();
                try {
                    return idempotencyService.acquireClaim(testUser1.getId(), key, fp, null);
                } finally {
                    endGate.countDown();
                }
            }));
        }

        startGate.countDown();
        assertThat(endGate.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        int acquiredCount = 0;
        int pendingCount = 0;

        for (Future<ClaimResult> future : futures) {
            ClaimResult result = future.get();
            if (result.isAcquired()) {
                acquiredCount++;
            } else if (result.isPending()) {
                pendingCount++;
            }
        }

        // Exactly one contender acquired the leader claim
        assertThat(acquiredCount).isEqualTo(1);
        assertThat(pendingCount).isEqualTo(7);

        // Database holds exactly one PENDING record
        List<MigrationIdempotencyRecord> all = idempotencyRepository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getStatus()).isEqualTo(IdempotencyClaimStatus.PENDING);
        assertThat(all.get(0).getClaimExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("PostgreSQL atomic takeover: expired PENDING claim without execution is safely taken over")
    void acquireClaim_expiredPendingClaim_takesOverClaimInPostgres() {
        String key = "claim-takeover-pg-002";
        String fp = "v1:takeoverFingerprint";

        // Pre-insert an expired PENDING claim
        MigrationIdempotencyRecord expired = new MigrationIdempotencyRecord(
                testUser1.getId(), key, fp, IdempotencyClaimStatus.PENDING,
                null, null, null, null, null,
                Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(30),
                null
        );
        idempotencyRepository.saveAndFlush(expired);

        ClaimResult result = idempotencyService.acquireClaim(testUser1.getId(), key, fp, null);

        assertThat(result.isAcquired()).isTrue();
        MigrationIdempotencyRecord recordInDb =
                idempotencyRepository.findByUserIdAndIdempotencyKey(testUser1.getId(), key).orElseThrow();
        assertThat(recordInDb.getStatus()).isEqualTo(IdempotencyClaimStatus.PENDING);
        assertThat(recordInDb.getClaimExpiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("Test A — Expired lease while original leader is alive: takeover increments generation; stale leader cannot continue")
    void testA_expiredLeaseWhileOriginalLeaderAlive_staleLeaderFencedOut() {
        String key = "test-a-fencing-001";
        String fp = "v1:testAFingerprint";

        // 1. Leader A acquires claim generation 1
        ClaimResult claimA = idempotencyService.acquireClaim(testUser1.getId(), key, fp, null);
        assertThat(claimA.isAcquired()).isTrue();
        String ownerA = claimA.claimOwner();
        long genA = claimA.claimGeneration();
        assertThat(ownerA).isNotNull();
        assertThat(genA).isEqualTo(1L);

        // 2. Simulate lease expiry in DB (claim_expires_at expired in the past)
        MigrationIdempotencyRecord record = idempotencyRepository.findByUserIdAndIdempotencyKey(testUser1.getId(), key).orElseThrow();
        record.setClaimExpiresAt(Instant.now().minusSeconds(10));
        idempotencyRepository.saveAndFlush(record);

        // 3. Leader B attempts takeover
        ClaimResult claimB = idempotencyService.acquireClaim(testUser1.getId(), key, fp, null);
        assertThat(claimB.isAcquired()).isTrue();
        String ownerB = claimB.claimOwner();
        long genB = claimB.claimGeneration();
        assertThat(ownerB).isNotEqualTo(ownerA);
        assertThat(genB).isEqualTo(2L);

        // 4. Stale Leader A tries to renew lease or complete
        boolean renewedByA = idempotencyService.renewClaim(testUser1.getId(), key, ownerA, genA, Duration.ofSeconds(60));
        assertThat(renewedByA).isFalse();

        // 5. Stale Leader A attempts to complete claim with generation 1
        assertThatThrownBy(() -> idempotencyService.completeClaim(
                testUser1.getId(), key, ownerA, genA, execution1.getId(), 202, "{}", "/api/migrations/" + execution1.getId()
        )).isInstanceOf(StaleClaimOwnershipException.class);

        // Verify Leader B's claim remains intact and PENDING with generation 2
        MigrationIdempotencyRecord recordInDb = idempotencyRepository.findByUserIdAndIdempotencyKey(testUser1.getId(), key).orElseThrow();
        assertThat(recordInDb.getStatus()).isEqualTo(IdempotencyClaimStatus.PENDING);
        assertThat(recordInDb.getClaimOwner()).isEqualTo(ownerB);
        assertThat(recordInDb.getClaimGeneration()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Test B — Heartbeat prevents takeover: leader renewals extend lease and block concurrent takeover")
    void testB_heartbeatPreventsTakeover_activeLeaderProtected() {
        String key = "test-b-heartbeat-002";
        String fp = "v1:testBFingerprint";

        // 1. Leader A acquires claim
        ClaimResult claimA = idempotencyService.acquireClaim(testUser1.getId(), key, fp, null);
        assertThat(claimA.isAcquired()).isTrue();
        String ownerA = claimA.claimOwner();
        long genA = claimA.claimGeneration();

        // 2. Leader A renews its lease
        boolean renewed = idempotencyService.renewClaim(testUser1.getId(), key, ownerA, genA, Duration.ofSeconds(120));
        assertThat(renewed).isTrue();

        // 3. Contender B attempts takeover
        ClaimResult claimB = idempotencyService.acquireClaim(testUser1.getId(), key, fp, null);
        // Because A's lease is valid and active, B receives PENDING (must wait/poll)
        assertThat(claimB.isPending()).isTrue();

        // Verify claim is still held by A with generation 1
        MigrationIdempotencyRecord recordInDb = idempotencyRepository.findByUserIdAndIdempotencyKey(testUser1.getId(), key).orElseThrow();
        assertThat(recordInDb.getClaimOwner()).isEqualTo(ownerA);
        assertThat(recordInDb.getClaimGeneration()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Test C — Stale leader cannot complete: completing with outdated generation affects 0 rows and throws")
    void testC_staleLeaderCannotComplete_newOwnerUncorrupted() {
        String key = "test-c-stale-complete-003";
        String fp = "v1:testCFingerprint";

        // 1. Leader A acquires generation 1
        ClaimResult claimA = idempotencyService.acquireClaim(testUser1.getId(), key, fp, null);
        String ownerA = claimA.claimOwner();
        long genA = claimA.claimGeneration();

        // 2. Expire lease
        MigrationIdempotencyRecord record = idempotencyRepository.findByUserIdAndIdempotencyKey(testUser1.getId(), key).orElseThrow();
        record.setClaimExpiresAt(Instant.now().minusSeconds(5));
        idempotencyRepository.saveAndFlush(record);

        // 3. Leader B legitimately takes over generation 2
        ClaimResult claimB = idempotencyService.acquireClaim(testUser1.getId(), key, fp, null);
        assertThat(claimB.isAcquired()).isTrue();
        String ownerB = claimB.claimOwner();
        long genB = claimB.claimGeneration();
        assertThat(genB).isEqualTo(2L);

        // 4. Stale leader A attempts completeClaim with gen 1
        assertThatThrownBy(() -> idempotencyService.completeClaim(
                testUser1.getId(), key, ownerA, genA, execution1.getId(), 202, "{\"stale\":true}", "/progress"
        )).isInstanceOf(StaleClaimOwnershipException.class);

        // 5. Verify B's claim remains intact and PENDING
        MigrationIdempotencyRecord recordInDb = idempotencyRepository.findByUserIdAndIdempotencyKey(testUser1.getId(), key).orElseThrow();
        assertThat(recordInDb.getStatus()).isEqualTo(IdempotencyClaimStatus.PENDING);
        assertThat(recordInDb.getClaimOwner()).isEqualTo(ownerB);
        assertThat(recordInDb.getClaimGeneration()).isEqualTo(genB);
        assertThat(recordInDb.getExecutionId()).isNull();

        // 6. Leader B completes successfully with gen 2
        MigrationIdempotencyRecord completedB = idempotencyService.completeClaim(
                testUser1.getId(), key, ownerB, genB, execution2.getId(), 202, "{\"leader\":\"B\"}", "/progress/b"
        );
        assertThat(completedB.getStatus()).isEqualTo(IdempotencyClaimStatus.COMPLETED);
        assertThat(completedB.getExecutionId()).isEqualTo(execution2.getId());
    }

    @Test
    @DisplayName("Test D — External operation ambiguity: playlist creation crash fails closed into reconciliation")
    void testD_externalOperationAmbiguity_failsClosedWithoutDuplicatePlaylist() {
        String key = "test-d-ambiguity-004";
        String fp = "v1:testDFingerprint";
        UUID sessionId = UUID.randomUUID();

        // 1. Leader A acquires claim
        ClaimResult claimA = idempotencyService.acquireClaim(testUser1.getId(), key, fp, sessionId);
        assertThat(claimA.isAcquired()).isTrue();

        // 2. Leader A simulates creating YouTube destination playlist, but crashes before creating MigrationExecution
        idempotencyService.markExternalOperationStartedForSession(sessionId);
        idempotencyService.recordDestinationPlaylistForSession(sessionId, "PL_youtube_dest_123");

        // 3. Lease expires
        MigrationIdempotencyRecord record = idempotencyRepository.findByUserIdAndIdempotencyKey(testUser1.getId(), key).orElseThrow();
        record.setClaimExpiresAt(Instant.now().minusSeconds(10));
        idempotencyRepository.saveAndFlush(record);

        // 4. Request B arrives on expired claim
        ClaimResult claimB = idempotencyService.acquireClaim(testUser1.getId(), key, fp, sessionId);

        // Must fail closed into RECONCILIATION_REQUIRED to prevent blindly creating another playlist!
        assertThat(claimB.isReconciliationRequired()).isTrue();
        assertThat(claimB.isAcquired()).isFalse();

        // Verify destination playlist ID is preserved for Phase 13F reconciliation
        MigrationIdempotencyRecord recordInDb = idempotencyRepository.findByUserIdAndIdempotencyKey(testUser1.getId(), key).orElseThrow();
        assertThat(recordInDb.getDestinationPlaylistId()).isEqualTo("PL_youtube_dest_123");
        assertThat(recordInDb.getStatus()).isEqualTo(IdempotencyClaimStatus.PENDING);
    }

    @Test
    @DisplayName("Test E — Queued execution recovery: recovered QUEUED execution delegates to Phase 13D scheduler safely")
    void testE_queuedExecutionRecovery_delegatesToSchedulerSafely() {
        String key = "test-e-recovery-005";
        String fp = "v1:testEFingerprint";
        ReviewSession session = reviewSessionRepository.save(
                new ReviewSession(testUser1, "src-playlist-e", "Source E", null)
        );

        // 1. Create a QUEUED execution representing execution persisted before dispatcher crash
        MigrationExecution queuedExec = createExecution(testUser1, MigrationExecutionStatus.QUEUED);
        queuedExec.setReviewSession(session);
        executionRepository.saveAndFlush(queuedExec);

        // 2. Pre-insert an expired PENDING claim pointing to this session
        MigrationIdempotencyRecord expiredClaim = new MigrationIdempotencyRecord(
                testUser1.getId(), key, fp, IdempotencyClaimStatus.PENDING,
                null, null, null, null, session.getId(),
                "stale-owner", 1L, Instant.now().minusSeconds(100), null,
                MigrationIdempotencyRecord.STAGE_ACQUIRED,
                Instant.now().minusSeconds(100),
                Instant.now().minusSeconds(10),
                null
        );
        idempotencyRepository.saveAndFlush(expiredClaim);

        // 3. New request arrives for recovery
        ClaimResult result = idempotencyService.acquireClaim(testUser1.getId(), key, fp, session.getId());

        // Recovers existing execution aggregate as REPLAY without creating a duplicate execution
        assertThat(result.isReplay()).isTrue();
        assertThat(result.getRecord()).isPresent();
        assertThat(result.getRecord().get().getExecutionId()).isEqualTo(queuedExec.getId());

        // 4. Verify no second execution was created
        List<MigrationExecution> allExecs = executionRepository.findAll();
        long sessionExecCount = allExecs.stream()
                .filter(e -> e.getReviewSession() != null && e.getReviewSession().getId().equals(session.getId()))
                .count();
        assertThat(sessionExecCount).isEqualTo(1);

        // 5. Verify the Phase 13D scheduler can claim this queued execution atomically
        Optional<com.spotifyyoutube.migrator.migration.reliability.scheduling.ClaimedExecution> schedulerClaim =
                executionRepository.claimNextEligibleExecution("scheduler-worker-1", 60);
        assertThat(schedulerClaim).isPresent();
        assertThat(schedulerClaim.get().executionId()).isEqualTo(queuedExec.getId());
    }

    @Test
    @DisplayName("Test F — Independent heartbeat renewal during a blocked external operation")
    void testF_independentHeartbeatRenewal_duringBlockedExternalOperation() throws Exception {
        String key = "test-f-heartbeat-blocked-006";
        String fp = "v1:testFFingerprint";

        // 1. Leader acquires claim
        ClaimResult claimA = idempotencyService.acquireClaim(testUser1.getId(), key, fp, null);
        assertThat(claimA.isAcquired()).isTrue();
        UUID claimId = claimA.getRecord().orElseThrow().getId();
        String ownerA = claimA.claimOwner();
        long genA = claimA.claimGeneration();

        // 2. Set up heartbeat service with fast 50ms interval and 120s extension
        IdempotencyClaimHeartbeatService heartbeatSvc = new IdempotencyClaimHeartbeatService(
                idempotencyRepository, transactionManager, null, 50L
        );

        try {
            // Register claim for background renewal
            heartbeatSvc.registerClaim(claimId, testUser1.getId(), key, ownerA, genA, Duration.ofSeconds(120));

            // Record initial database expiration timestamp
            MigrationIdempotencyRecord initialRecord = idempotencyRepository.findById(claimId).orElseThrow();
            Instant initialExpiresAt = initialRecord.getClaimExpiresAt();

            // 3. Simulate caller blocked in external YouTube operation for 250ms
            Thread.sleep(250);

            // 4. Verify background heartbeat renewed the claim in PostgreSQL independently of caller thread
            MigrationIdempotencyRecord renewedRecord = idempotencyRepository.findById(claimId).orElseThrow();
            assertThat(renewedRecord.getHeartbeatAt()).isNotNull();
            assertThat(renewedRecord.getClaimExpiresAt()).isAfter(initialExpiresAt);
            assertThat(renewedRecord.getClaimOwner()).isEqualTo(ownerA);
            assertThat(renewedRecord.getClaimGeneration()).isEqualTo(genA);
        } finally {
            heartbeatSvc.unregisterClaim(claimId);
            heartbeatSvc.shutdown();
        }
    }

    @Test
    @DisplayName("Test G — Heartbeat shutdown after ownership loss")
    void testG_heartbeatShutdown_afterOwnershipLoss() {
        String key = "test-g-heartbeat-shutdown-007";
        String fp = "v1:testGFingerprint";

        // 1. Leader acquires claim
        ClaimResult claimA = idempotencyService.acquireClaim(testUser1.getId(), key, fp, null);
        assertThat(claimA.isAcquired()).isTrue();
        UUID claimId = claimA.getRecord().orElseThrow().getId();
        String ownerA = claimA.claimOwner();
        long genA = claimA.claimGeneration();

        IdempotencyClaimHeartbeatService heartbeatSvc = new IdempotencyClaimHeartbeatService(
                idempotencyRepository, transactionManager, null, 5000L
        );

        try {
            heartbeatSvc.registerClaim(claimId, testUser1.getId(), key, ownerA, genA, Duration.ofSeconds(60));
            assertThat(heartbeatSvc.isOwnershipLost(claimId)).isFalse();

            // 2. Simulate takeover advancing generation in database to gen 2 by a new owner
            MigrationIdempotencyRecord record = idempotencyRepository.findById(claimId).orElseThrow();
            record.setClaimOwner("new-owner-b");
            record.setClaimGeneration(2L);
            idempotencyRepository.saveAndFlush(record);

            // 3. Trigger heartbeat renewal directly
            boolean renewed = heartbeatSvc.renewClaimDirectly(claimId);

            // 4. Renewal must return false and mark ownership as lost
            assertThat(renewed).isFalse();
            assertThat(heartbeatSvc.isOwnershipLost(claimId)).isTrue();
        } finally {
            heartbeatSvc.unregisterClaim(claimId);
            heartbeatSvc.shutdown();
        }
    }

    @Test
    @DisplayName("Test H — Stale leader cannot record a playlist ID")
    void testH_staleLeaderCannotRecordPlaylistId() {
        String key = "test-h-stale-record-008";
        String fp = "v1:testHFingerprint";
        UUID sessionId = UUID.randomUUID();

        // 1. Leader A acquires claim
        ClaimResult claimA = idempotencyService.acquireClaim(testUser1.getId(), key, fp, sessionId);
        assertThat(claimA.isAcquired()).isTrue();
        UUID claimId = claimA.getRecord().orElseThrow().getId();
        String ownerA = claimA.claimOwner();
        long genA = claimA.claimGeneration();

        // 2. Simulate takeover advancing generation to 2 by Leader B
        MigrationIdempotencyRecord record = idempotencyRepository.findById(claimId).orElseThrow();
        record.setClaimOwner("new-leader-b");
        record.setClaimGeneration(2L);
        idempotencyRepository.saveAndFlush(record);

        // 3. Stale Leader A attempts to record playlist ID using old fencing token in ClaimOwnershipContext
        ClaimOwnershipContext.set(new ClaimOwnershipContext(claimId, testUser1.getId(), key, ownerA, genA));
        try {
            assertThatThrownBy(() -> idempotencyService.recordDestinationPlaylistForSession(
                    sessionId, "PL_stale_dest_456"
            )).isInstanceOf(StaleClaimOwnershipException.class);
        } finally {
            ClaimOwnershipContext.clear();
        }

        // 4. Verify database record has NOT been updated with the stale leader's playlist ID
        MigrationIdempotencyRecord recordInDb = idempotencyRepository.findById(claimId).orElseThrow();
        assertThat(recordInDb.getDestinationPlaylistId()).isNull();
        assertThat(recordInDb.getClaimGeneration()).isEqualTo(2L);
        assertThat(recordInDb.getClaimOwner()).isEqualTo("new-leader-b");
    }

    @Test
    @DisplayName("Test I — State B cannot be taken over for another external mutation")
    void testI_stateBCannotBeTakenOverForAnotherExternalMutation() {
        String key = "test-i-state-b-takeover-009";
        String fp = "v1:testIFingerprint";
        UUID sessionId = UUID.randomUUID();

        // 1. Leader A acquires claim and starts external operation (State B)
        ClaimResult claimA = idempotencyService.acquireClaim(testUser1.getId(), key, fp, sessionId);
        assertThat(claimA.isAcquired()).isTrue();
        UUID claimId = claimA.getRecord().orElseThrow().getId();
        long genA = claimA.claimGeneration();

        idempotencyService.markExternalOperationStartedForSession(sessionId);

        // 2. Simulate lease expiry in the past
        MigrationIdempotencyRecord record = idempotencyRepository.findById(claimId).orElseThrow();
        record.setClaimExpiresAt(Instant.now().minusSeconds(30));
        idempotencyRepository.saveAndFlush(record);

        // 3. Direct SQL takeover attempt must fail with 0 rows because claim_stage != 'ACQUIRED'
        int sqlTakeoverRows = idempotencyRepository.takeOverClaimWithFencingDatabaseTime(
                claimId, "contender-c", genA, 60L
        );
        assertThat(sqlTakeoverRows).isEqualTo(0);

        // 4. Service-level acquireClaim attempt must fail closed into RECONCILIATION_REQUIRED
        ClaimResult contenderClaim = idempotencyService.acquireClaim(testUser1.getId(), key, fp, sessionId);
        assertThat(contenderClaim.isReconciliationRequired()).isTrue();
        assertThat(contenderClaim.isAcquired()).isFalse();

        // Verify the record is still owned by the original leader at gen 1, still in State B
        MigrationIdempotencyRecord recordInDb = idempotencyRepository.findById(claimId).orElseThrow();
        assertThat(recordInDb.getClaimGeneration()).isEqualTo(genA);
        assertThat(recordInDb.getClaimStage()).isEqualTo(MigrationIdempotencyRecord.STAGE_EXTERNAL_OPERATION_STARTED);
    }
}

