package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultMigrationIdempotencyService Unit Tests (Phase 13E)")
class DefaultMigrationIdempotencyServiceTest {

    @Mock
    private MigrationIdempotencyRepository repository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private Clock fixedClock;
    private Instant baseTime;
    private DefaultMigrationIdempotencyService service;

    private UUID userId;
    private UUID otherUserId;
    private String idempotencyKey;
    private String fingerprint;
    private UUID executionId;

    @BeforeEach
    void setUp() {
        baseTime = Instant.parse("2026-11-09T12:00:00Z");
        fixedClock = Clock.fixed(baseTime, ZoneOffset.UTC);

        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        service = new DefaultMigrationIdempotencyService(repository, transactionManager, fixedClock);

        userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        otherUserId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        idempotencyKey = "tx-key-100";
        fingerprint = "v1:abc123canonicalhash";
        executionId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    }

    @Nested
    @DisplayName("Record & Replay Semantics")
    class RecordAndReplayTests {

        @Test
        @DisplayName("First request creates active record with expires_at = NULL")
        void recordSubmission_firstTime_createsRecordWithNullExpiresAt() {
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
            assertThat(recorded.getRequestFingerprint()).isEqualTo(fingerprint);
            assertThat(recorded.getExecutionId()).isEqualTo(executionId);
            assertThat(recorded.getResponseStatus()).isEqualTo(202);
            assertThat(recorded.getExpiresAt()).isNull(); // Active execution: expires_at must be NULL
        }

        @Test
        @DisplayName("Matching key and fingerprint returns existing record for replay without modifying DB")
        void recordSubmission_existingKeyAndMatchingFingerprint_replaysResponse() {
            MigrationIdempotencyRecord existing = MigrationIdempotencyRecord.createActive(
                    userId, idempotencyKey, fingerprint, executionId, 202, "{\"status\":\"QUEUED\"}"
            );

            when(repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
                    .thenReturn(Optional.of(existing));

            MigrationIdempotencyRecord replayed = service.recordSubmission(
                    userId, idempotencyKey, fingerprint, UUID.randomUUID(), 200, "{\"other\":\"payload\"}"
            );

            assertThat(replayed).isSameAs(existing);
            assertThat(replayed.getExecutionId()).isEqualTo(executionId);
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("Existing key with different fingerprint throws IdempotencyFingerprintMismatchException (409)")
        void recordSubmission_existingKeyAndDifferentFingerprint_throwsConflict() {
            MigrationIdempotencyRecord existing = MigrationIdempotencyRecord.createActive(
                    userId, idempotencyKey, fingerprint, executionId, 202, "{\"status\":\"QUEUED\"}"
            );

            when(repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
                    .thenReturn(Optional.of(existing));

            String differentFingerprint = "v1:differentPayloadFingerprint999";

            assertThatThrownBy(() -> service.recordSubmission(
                    userId, idempotencyKey, differentFingerprint, executionId, 202, "{}"
            )).isInstanceOf(IdempotencyFingerprintMismatchException.class)
                    .hasMessageContaining(idempotencyKey);
        }

        @Test
        @DisplayName("Concurrent duplicate catching DataIntegrityViolation replays winner on matching fingerprint")
        void recordSubmission_concurrentRaceWinner_replaysResponse() {
            // Initially empty find
            when(repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(MigrationIdempotencyRecord.createActive(
                            userId, idempotencyKey, fingerprint, executionId, 202, "{\"winner\":true}"
                    )));

            when(repository.saveAndFlush(any(MigrationIdempotencyRecord.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate key value"));

            MigrationIdempotencyRecord result = service.recordSubmission(
                    userId, idempotencyKey, fingerprint, executionId, 202, "{}"
            );

            assertThat(result).isNotNull();
            assertThat(result.getResponsePayload()).isEqualTo("{\"winner\":true}");
        }

        @Test
        @DisplayName("Concurrent race catching DataIntegrityViolation with mismatched winner throws conflict (409)")
        void recordSubmission_concurrentRaceWinnerDifferentFingerprint_throwsConflict() {
            when(repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(MigrationIdempotencyRecord.createActive(
                            userId, idempotencyKey, "v1:otherFingerprintFromWinner", executionId, 202, "{}"
                    )));

            when(repository.saveAndFlush(any(MigrationIdempotencyRecord.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate key value"));

            assertThatThrownBy(() -> service.recordSubmission(
                    userId, idempotencyKey, fingerprint, executionId, 202, "{}"
            )).isInstanceOf(IdempotencyFingerprintMismatchException.class)
                    .hasMessageContaining(idempotencyKey);
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
            // First call sets expiry (returns 1 updated)
            Instant initialTerminalTime = Instant.parse("2026-11-09T10:00:00Z");
            when(repository.setTerminalExpiry(executionId, initialTerminalTime.plus(Duration.ofHours(72))))
                    .thenReturn(1);

            int firstUpdate = service.markExecutionTerminal(executionId, initialTerminalTime);
            assertThat(firstUpdate).isEqualTo(1);

            // Second call with later timestamp returns 0 because expires_at is already set
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
}
