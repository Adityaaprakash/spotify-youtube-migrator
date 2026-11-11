package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultMigrationIdempotencyService Unit Tests (Phase 13E)")
class DefaultMigrationIdempotencyServiceTest {

    @Mock
    private MigrationIdempotencyRepository repository;

    @Mock
    private MigrationExecutionRepository executionRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private IdempotencyClaimHeartbeatService claimHeartbeatService;

    private Clock fixedClock;
    private Instant baseTime;
    private ObjectMapper objectMapper;
    private DefaultMigrationIdempotencyService service;

    private UUID userId;
    private UUID otherUserId;
    private String idempotencyKey;
    private String fingerprint;
    private UUID executionId;
    private UUID reviewSessionId;

    @BeforeEach
    void setUp() {
        baseTime = Instant.parse("2026-11-09T12:00:00Z");
        fixedClock = Clock.fixed(baseTime, ZoneOffset.UTC);
        objectMapper = new ObjectMapper();

        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        service = new DefaultMigrationIdempotencyService(
                repository, executionRepository, transactionManager, fixedClock, objectMapper, claimHeartbeatService
        );


        userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        otherUserId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        idempotencyKey = "tx-key-100";
        fingerprint = "v1:abc123canonicalhash";
        executionId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        reviewSessionId = UUID.fromString("44444444-4444-4444-4444-444444444444");
    }

    @Nested
    @DisplayName("Atomic Claim Protocol Tests")
    class AtomicClaimTests {

        @Test
        @DisplayName("New key acquires PENDING claim with 60-second timeout")
        void acquireClaim_newKey_acquiresPendingClaim() {
            when(repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
                    .thenReturn(Optional.empty());

            when(repository.saveAndFlush(any(MigrationIdempotencyRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            ClaimResult result = service.acquireClaim(userId, idempotencyKey, fingerprint, reviewSessionId);

            assertThat(result.isAcquired()).isTrue();
            assertThat(result.getRecord()).isPresent();
            MigrationIdempotencyRecord record = result.getRecord().get();
            assertThat(record.getStatus()).isEqualTo(IdempotencyClaimStatus.PENDING);
            assertThat(record.getExecutionId()).isNull();
            assertThat(record.getClaimExpiresAt()).isEqualTo(baseTime.plusSeconds(60));
        }

        @Test
        @DisplayName("Completed record with matching fingerprint returns REPLAY")
        void acquireClaim_completedMatching_returnsReplay() {
            MigrationIdempotencyRecord completed = new MigrationIdempotencyRecord(
                    userId, idempotencyKey, fingerprint, IdempotencyClaimStatus.COMPLETED,
                    executionId, 202, "{\"status\":\"QUEUED\"}", "/api/progress",
                    reviewSessionId, baseTime, null, null
            );

            when(repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
                    .thenReturn(Optional.of(completed));

            ClaimResult result = service.acquireClaim(userId, idempotencyKey, fingerprint, reviewSessionId);

            assertThat(result.isReplay()).isTrue();
            assertThat(result.getRecord().get().getExecutionId()).isEqualTo(executionId);
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("Existing record with different fingerprint returns MISMATCH")
        void acquireClaim_fingerprintMismatch_returnsMismatch() {
            MigrationIdempotencyRecord existing = new MigrationIdempotencyRecord(
                    userId, idempotencyKey, "v1:differentFingerprint", IdempotencyClaimStatus.COMPLETED,
                    executionId, 202, "{}", null, null, baseTime, null, null
            );

            when(repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
                    .thenReturn(Optional.of(existing));

            ClaimResult result = service.acquireClaim(userId, idempotencyKey, fingerprint, reviewSessionId);

            assertThat(result.isMismatch()).isTrue();
            assertThat(result.message()).contains("previously used with a different request payload");
        }

        @Test
        @DisplayName("Active PENDING claim with matching fingerprint returns PENDING for follower to poll")
        void acquireClaim_activePending_returnsPending() {
            MigrationIdempotencyRecord pending = MigrationIdempotencyRecord.createPending(
                    userId, idempotencyKey, fingerprint, reviewSessionId, baseTime.plusSeconds(45)
            );

            when(repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
                    .thenReturn(Optional.of(pending));

            ClaimResult result = service.acquireClaim(userId, idempotencyKey, fingerprint, reviewSessionId);

            assertThat(result.isPending()).isTrue();
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("Expired PENDING claim without execution aggregate allows takeover and returns ACQUIRED")
        void acquireClaim_expiredPendingWithoutExecution_takesOverClaim() {
            MigrationIdempotencyRecord expired = MigrationIdempotencyRecord.createPending(
                    userId, idempotencyKey, fingerprint, reviewSessionId, baseTime.minusSeconds(10)
            );

            when(repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
                    .thenReturn(Optional.of(expired));

            when(executionRepository.findByReviewSessionId(reviewSessionId))
                    .thenReturn(Optional.empty());

            when(repository.takeOverClaimWithFencingDatabaseTime(any(), any(), anyLong(), anyLong())).thenReturn(1);

            ClaimResult result = service.acquireClaim(userId, idempotencyKey, fingerprint, reviewSessionId);

            assertThat(result.isAcquired()).isTrue();
            assertThat(result.claimGeneration()).isEqualTo(2L);
            verify(repository).takeOverClaimWithFencingDatabaseTime(
                    eq(expired.getId()), any(), eq(1L), eq(60L)
            );
        }

        @Test
        @DisplayName("Expired PENDING claim where external operation started returns RECONCILIATION_REQUIRED")
        void acquireClaim_expiredPendingWithExternalOperationStarted_returnsReconciliationRequired() {
            MigrationIdempotencyRecord expired = MigrationIdempotencyRecord.createPending(
                    userId, idempotencyKey, fingerprint, reviewSessionId, baseTime.minusSeconds(10)
            );
            expired.markExternalOperationStarted();

            when(repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
                    .thenReturn(Optional.of(expired));

            when(executionRepository.findByReviewSessionId(reviewSessionId))
                    .thenReturn(Optional.empty());

            ClaimResult result = service.acquireClaim(userId, idempotencyKey, fingerprint, reviewSessionId);

            assertThat(result.isReconciliationRequired()).isTrue();
            assertThat(result.isAcquired()).isFalse();
            verify(repository, never()).takeOverClaimWithFencingDatabaseTime(any(), any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("Expired PENDING claim with destination playlist ID returns RECONCILIATION_REQUIRED")
        void acquireClaim_expiredPendingWithPlaylistId_returnsReconciliationRequired() {
            MigrationIdempotencyRecord expired = MigrationIdempotencyRecord.createPending(
                    userId, idempotencyKey, fingerprint, reviewSessionId, baseTime.minusSeconds(10)
            );
            expired.recordDestinationPlaylist("PL_remote_dest_xyz");

            when(repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
                    .thenReturn(Optional.of(expired));

            when(executionRepository.findByReviewSessionId(reviewSessionId))
                    .thenReturn(Optional.empty());

            ClaimResult result = service.acquireClaim(userId, idempotencyKey, fingerprint, reviewSessionId);

            assertThat(result.isReconciliationRequired()).isTrue();
            assertThat(result.isAcquired()).isFalse();
            verify(repository, never()).takeOverClaimWithFencingDatabaseTime(any(), any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("Expired PENDING claim with existing execution aggregate reconciles execution and returns REPLAY")
        void acquireClaim_expiredPendingWithExecution_reconcilesAndReturnsReplay() {
            MigrationIdempotencyRecord expired = MigrationIdempotencyRecord.createPending(
                    userId, idempotencyKey, fingerprint, reviewSessionId, baseTime.minusSeconds(10)
            );

            when(repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
                    .thenReturn(Optional.of(expired));

            when(repository.recoverClaimWithFencing(eq(expired.getId()), eq(1L), eq(executionId), eq(202), any(), any()))
                    .thenAnswer(inv -> {
                        expired.markCompleted(executionId, 202, (String) inv.getArgument(4), (String) inv.getArgument(5));
                        return 1;
                    });

            ReviewSession mockSession = org.mockito.Mockito.mock(ReviewSession.class);
            when(mockSession.getId()).thenReturn(reviewSessionId);

            MigrationExecution mockExecution = org.mockito.Mockito.mock(MigrationExecution.class);
            when(mockExecution.getId()).thenReturn(executionId);
            when(mockExecution.getReviewSession()).thenReturn(mockSession);
            when(mockExecution.getStatus()).thenReturn(MigrationExecutionStatus.QUEUED);
            when(mockExecution.getTotalApprovedTracks()).thenReturn(10);
            when(mockExecution.getCreatedAt()).thenReturn(baseTime);

            when(executionRepository.findByReviewSessionId(reviewSessionId))
                    .thenReturn(Optional.of(mockExecution));
            when(executionRepository.findById(executionId))
                    .thenReturn(Optional.of(mockExecution));

            ClaimResult result = service.acquireClaim(userId, idempotencyKey, fingerprint, reviewSessionId);

            assertThat(result.isReplay()).isTrue();
            verify(repository).recoverClaimWithFencing(
                    eq(expired.getId()), eq(1L), eq(executionId), eq(202), any(), eq("/api/migrations/" + executionId + "/progress")
            );
        }

        @Test
        @DisplayName("completeClaim with fencing verifies owner and generation")
        void completeClaim_withFencing_success() {
            MigrationIdempotencyRecord pending = MigrationIdempotencyRecord.createPending(
                    userId, idempotencyKey, fingerprint, reviewSessionId, baseTime.plusSeconds(30)
            );

            when(repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
                    .thenReturn(Optional.of(pending));
            when(repository.completeClaimWithFencing(any(), eq("owner-token"), eq(1L), eq(executionId), eq(202), any(), any()))
                    .thenReturn(1);

            MigrationIdempotencyRecord completed = service.completeClaim(
                    userId, idempotencyKey, "owner-token", 1L, executionId, 202, "{\"status\":\"QUEUED\"}", "/progress"
            );

            assertThat(completed.getStatus()).isEqualTo(IdempotencyClaimStatus.COMPLETED);
            assertThat(completed.getExecutionId()).isEqualTo(executionId);
        }

        @Test
        @DisplayName("completeClaim with fencing throws StaleClaimOwnershipException when 0 rows updated")
        void completeClaim_withFencing_staleOwner_throwsException() {
            MigrationIdempotencyRecord pending = MigrationIdempotencyRecord.createPending(
                    userId, idempotencyKey, fingerprint, reviewSessionId, baseTime.plusSeconds(30)
            );

            when(repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
                    .thenReturn(Optional.of(pending));
            when(repository.completeClaimWithFencing(any(), eq("stale-owner"), eq(1L), eq(executionId), eq(202), any(), any()))
                    .thenReturn(0);

            assertThatThrownBy(() -> service.completeClaim(
                    userId, idempotencyKey, "stale-owner", 1L, executionId, 202, "{\"status\":\"QUEUED\"}", "/progress"
            )).isInstanceOf(StaleClaimOwnershipException.class);
        }

        @Test
        @DisplayName("renewClaim extends lease when owner and generation match")
        void renewClaim_success() {
            MigrationIdempotencyRecord pending = MigrationIdempotencyRecord.createPending(
                    userId, idempotencyKey, fingerprint, reviewSessionId, baseTime.plusSeconds(30)
            );

            when(repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
                    .thenReturn(Optional.of(pending));
            when(repository.renewClaimDatabaseTime(any(), eq("owner-1"), eq(1L), eq(60L)))
                    .thenReturn(1);

            boolean renewed = service.renewClaim(userId, idempotencyKey, "owner-1", 1L, Duration.ofSeconds(60));
            assertThat(renewed).isTrue();
        }

        @Test
        @DisplayName("completeClaim transitions PENDING claim to COMPLETED and clears claimExpiresAt")
        void completeClaim_transitionsToCompleted() {
            MigrationIdempotencyRecord pending = MigrationIdempotencyRecord.createPending(
                    userId, idempotencyKey, fingerprint, reviewSessionId, baseTime.plusSeconds(30)
            );

            when(repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
                    .thenReturn(Optional.of(pending));

            MigrationIdempotencyRecord completed = service.completeClaim(
                    userId, idempotencyKey, executionId, 202, "{\"status\":\"QUEUED\"}", "/progress"
            );

            assertThat(completed.getStatus()).isEqualTo(IdempotencyClaimStatus.COMPLETED);
            assertThat(completed.getExecutionId()).isEqualTo(executionId);
            assertThat(completed.getClaimExpiresAt()).isNull();
            verify(repository).completeClaim(
                    eq(pending.getId()), eq(executionId), eq(202), eq("{\"status\":\"QUEUED\"}"), eq("/progress")
            );
        }

        @Test
        @DisplayName("releaseClaim deletes pending claim without execution so key can be retried")
        void releaseClaim_deletesPendingRecord() {
            service.releaseClaim(userId, idempotencyKey);

            verify(repository).deletePendingClaim(userId, idempotencyKey, IdempotencyClaimStatus.PENDING);
        }
    }

    @Nested
    @DisplayName("Record & Replay Semantics")
    class RecordAndReplayTests {

        @Test
        @DisplayName("recordSubmission creates completed record when called directly")
        void recordSubmission_firstTime_createsRecord() {
            when(repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
                    .thenReturn(Optional.empty());

            when(repository.saveAndFlush(any(MigrationIdempotencyRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            MigrationIdempotencyRecord recorded = service.recordSubmission(
                    userId, idempotencyKey, fingerprint, executionId, 202,
                    "{\"executionId\":\"" + executionId + "\"}"
            );

            assertThat(recorded).isNotNull();
            assertThat(recorded.getUserId()).isEqualTo(userId);
            assertThat(recorded.getIdempotencyKey()).isEqualTo(idempotencyKey);
            assertThat(recorded.getExecutionId()).isEqualTo(executionId);
            assertThat(recorded.getResponseStatus()).isEqualTo(202);
            assertThat(recorded.getExpiresAt()).isNull();
        }

        @Test
        @DisplayName("Same key under different users does not collide")
        void sameKey_underDifferentUsers_doesNotCollide() {
            when(repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
                    .thenReturn(Optional.empty());
            when(repository.findByUserIdAndIdempotencyKey(otherUserId, idempotencyKey))
                    .thenReturn(Optional.empty());

            when(repository.saveAndFlush(any(MigrationIdempotencyRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            MigrationIdempotencyRecord recordUser1 = service.recordSubmission(
                    userId, idempotencyKey, fingerprint, executionId, 202, "{}"
            );
            MigrationIdempotencyRecord recordUser2 = service.recordSubmission(
                    otherUserId, idempotencyKey, fingerprint, UUID.randomUUID(), 202, "{}"
            );

            assertThat(recordUser1.getUserId()).isEqualTo(userId);
            assertThat(recordUser2.getUserId()).isEqualTo(otherUserId);
            assertThat(recordUser1.getIdempotencyKey()).isEqualTo(recordUser2.getIdempotencyKey());
        }
    }

    @Nested
    @DisplayName("Terminal-Only Retention Lifecycle")
    class RetentionLifecycleTests {

        @Test
        @DisplayName("Terminal transition sets expires_at to terminalTransitionTime + 72 hours")
        void markExecutionTerminal_sets72HourRetention() {
            Instant terminalTime = Instant.parse("2026-11-09T15:30:00Z");
            Instant expectedExpiry = terminalTime.plus(Duration.ofHours(72));

            when(repository.setTerminalExpiry(executionId, expectedExpiry))
                    .thenReturn(1);

            int updated = service.markExecutionTerminal(executionId, terminalTime);

            assertThat(updated).isEqualTo(1);
            verify(repository).setTerminalExpiry(executionId, expectedExpiry);
        }

        @Test
        @DisplayName("Null terminal transition time defaults to clock instant")
        void markExecutionTerminal_nullTime_usesClockTime() {
            Instant expectedExpiry = baseTime.plus(Duration.ofHours(72));

            when(repository.setTerminalExpiry(executionId, expectedExpiry))
                    .thenReturn(1);

            int updated = service.markExecutionTerminal(executionId, null);

            assertThat(updated).isEqualTo(1);
            verify(repository).setTerminalExpiry(executionId, expectedExpiry);
        }

        @Test
        @DisplayName("Repeated terminal transition does not extend retention (handled by WHERE expires_at IS NULL)")
        void repeatedTerminalTransition_doesNotExtendRetention() {
            Instant initialTerminalTime = Instant.parse("2026-11-09T10:00:00Z");
            when(repository.setTerminalExpiry(executionId, initialTerminalTime.plus(Duration.ofHours(72))))
                    .thenReturn(1);

            int firstUpdate = service.markExecutionTerminal(executionId, initialTerminalTime);
            assertThat(firstUpdate).isEqualTo(1);

            Instant laterTerminalTime = Instant.parse("2026-11-09T20:00:00Z");
            when(repository.setTerminalExpiry(executionId, laterTerminalTime.plus(Duration.ofHours(72))))
                    .thenReturn(0);

            int secondUpdate = service.markExecutionTerminal(executionId, laterTerminalTime);
            assertThat(secondUpdate).isEqualTo(0);
        }

        @Test
        @DisplayName("Cleanup deletes only expired records and ignores active records")
        void cleanupExpiredRecords_cutoffBased_deletesOnlyExpired() {
            Instant cutoff = Instant.parse("2026-11-12T12:00:00Z");
            when(repository.deleteExpiredBefore(cutoff)).thenReturn(5);

            int deleted = service.cleanupExpiredRecords(cutoff);

            assertThat(deleted).isEqualTo(5);
            verify(repository).deleteExpiredBefore(cutoff);
        }

        @Test
        @DisplayName("Scheduled database clock cleanup sweeper purges expired records using default batch size")
        void cleanupExpiredRecords_scheduledDatabaseClock_deletesExpiredRecords() {
            int batchSize = DefaultMigrationIdempotencyService.DEFAULT_CLEANUP_BATCH_SIZE;
            when(repository.deleteExpiredDatabaseTime(batchSize)).thenReturn(12);

            int deleted = service.cleanupExpiredRecords();

            assertThat(deleted).isEqualTo(12);
            verify(repository).deleteExpiredDatabaseTime(batchSize);
        }

        @Test
        @DisplayName("Scheduled cleanup failure is safely caught and returns 0 without interrupting execution")
        void cleanupExpiredRecords_scheduledDatabaseClock_handlesExceptionSafely() {
            when(repository.deleteExpiredDatabaseTime(any(Integer.class)))
                    .thenThrow(new RuntimeException("Simulated database timeout"));

            int deleted = service.cleanupExpiredRecords();

            assertThat(deleted).isZero();
        }
    }

    @Nested
    @DisplayName("Stage Transition and Fencing Tests")
    class StageTransitionFencingTests {

        @Test
        @DisplayName("markExternalOperationStartedForSession with ClaimOwnershipContext updates stage with fencing")
        void markExternalOperationStarted_withContext_success() {
            UUID claimId = UUID.randomUUID();
            ClaimOwnershipContext.set(new ClaimOwnershipContext(claimId, userId, idempotencyKey, "owner-1", 1L));

            try {
                when(repository.markExternalOperationStartedWithFencing(claimId, "owner-1", 1L))
                        .thenReturn(1);

                int rows = service.markExternalOperationStartedForSession(reviewSessionId);

                assertThat(rows).isEqualTo(1);
                verify(repository).markExternalOperationStartedWithFencing(claimId, "owner-1", 1L);
            } finally {
                ClaimOwnershipContext.clear();
            }
        }

        @Test
        @DisplayName("markExternalOperationStartedForSession throws StaleClaimOwnershipException when 0 rows updated")
        void markExternalOperationStarted_staleContext_throwsStaleException() {
            UUID claimId = UUID.randomUUID();
            ClaimOwnershipContext.set(new ClaimOwnershipContext(claimId, userId, idempotencyKey, "stale-owner", 1L));

            try {
                when(repository.markExternalOperationStartedWithFencing(claimId, "stale-owner", 1L))
                        .thenReturn(0);

                assertThatThrownBy(() -> service.markExternalOperationStartedForSession(reviewSessionId))
                        .isInstanceOf(StaleClaimOwnershipException.class);
            } finally {
                ClaimOwnershipContext.clear();
            }
        }

        @Test
        @DisplayName("markExternalOperationStartedForSession throws StaleClaimOwnershipException when heartbeat lost ownership")
        void markExternalOperationStarted_heartbeatLostOwnership_throwsStaleException() {
            UUID claimId = UUID.randomUUID();
            ClaimOwnershipContext.set(new ClaimOwnershipContext(claimId, userId, idempotencyKey, "owner-1", 1L));

            try {
                when(claimHeartbeatService.isOwnershipLost(claimId)).thenReturn(true);

                assertThatThrownBy(() -> service.markExternalOperationStartedForSession(reviewSessionId))
                        .isInstanceOf(StaleClaimOwnershipException.class);

                verify(repository, never()).markExternalOperationStartedWithFencing(any(), any(), anyLong());
            } finally {
                ClaimOwnershipContext.clear();
            }
        }

        @Test
        @DisplayName("recordDestinationPlaylistForSession with ClaimOwnershipContext updates playlist with fencing")
        void recordDestinationPlaylist_withContext_success() {
            UUID claimId = UUID.randomUUID();
            ClaimOwnershipContext.set(new ClaimOwnershipContext(claimId, userId, idempotencyKey, "owner-1", 1L));

            try {
                when(repository.recordDestinationPlaylistWithFencing(claimId, "owner-1", 1L, "PL_youtube_123"))
                        .thenReturn(1);

                int rows = service.recordDestinationPlaylistForSession(reviewSessionId, "PL_youtube_123");

                assertThat(rows).isEqualTo(1);
                verify(repository).recordDestinationPlaylistWithFencing(claimId, "owner-1", 1L, "PL_youtube_123");
            } finally {
                ClaimOwnershipContext.clear();
            }
        }

        @Test
        @DisplayName("recordDestinationPlaylistForSession throws StaleClaimOwnershipException when 0 rows updated")
        void recordDestinationPlaylist_staleContext_throwsStaleException() {
            UUID claimId = UUID.randomUUID();
            ClaimOwnershipContext.set(new ClaimOwnershipContext(claimId, userId, idempotencyKey, "stale-owner", 1L));

            try {
                when(repository.recordDestinationPlaylistWithFencing(claimId, "stale-owner", 1L, "PL_youtube_123"))
                        .thenReturn(0);

                assertThatThrownBy(() -> service.recordDestinationPlaylistForSession(reviewSessionId, "PL_youtube_123"))
                        .isInstanceOf(StaleClaimOwnershipException.class);
            } finally {
                ClaimOwnershipContext.clear();
            }
        }
    }
}

