package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyClaimHeartbeatService Unit Tests (Phase 13E)")
class IdempotencyClaimHeartbeatServiceTest {

    @Mock
    private MigrationIdempotencyRepository repository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private ScheduledExecutorService scheduler;
    private IdempotencyClaimHeartbeatService heartbeatService;

    private UUID claimId;
    private UUID userId;
    private String idempotencyKey;
    private String claimOwner;
    private long claimGeneration;

    @BeforeEach
    void setUp() {
        claimId = UUID.randomUUID();
        userId = UUID.randomUUID();
        idempotencyKey = "test-idem-key-100";
        claimOwner = UUID.randomUUID().toString();
        claimGeneration = 1L;

        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        scheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatService = new IdempotencyClaimHeartbeatService(
                repository,
                transactionManager,
                scheduler,
                15_000L,
                3
        );
    }

    @AfterEach
    void tearDown() {
        heartbeatService.shutdown();
        ClaimOwnershipContext.clear();
    }

    @Test
    @DisplayName("Successful heartbeat renewal confirms ownership and resets consecutive failure counter")
    void renewClaim_success_confirmsOwnershipAndResetsFailures() {
        heartbeatService.registerClaim(claimId, userId, idempotencyKey, claimOwner, claimGeneration, Duration.ofSeconds(60));

        when(repository.renewClaimDatabaseTime(eq(claimId), eq(claimOwner), eq(claimGeneration), anyLong()))
                .thenReturn(1);

        boolean renewed = heartbeatService.renewClaimDirectly(claimId);

        assertThat(renewed).isTrue();
        assertThat(heartbeatService.isOwnershipLost(claimId)).isFalse();
        assertThat(heartbeatService.getConsecutiveFailures(claimId)).isEqualTo(0);
        verify(repository).renewClaimDatabaseTime(eq(claimId), eq(claimOwner), eq(claimGeneration), anyLong());
    }

    @Test
    @DisplayName("Zero-row renewal immediately marks ownership lost and cancels future renewals")
    void renewClaim_zeroRows_immediatelyMarksOwnershipLost() {
        heartbeatService.registerClaim(claimId, userId, idempotencyKey, claimOwner, claimGeneration, Duration.ofSeconds(60));

        when(repository.renewClaimDatabaseTime(eq(claimId), eq(claimOwner), eq(claimGeneration), anyLong()))
                .thenReturn(0);

        boolean renewed = heartbeatService.renewClaimDirectly(claimId);

        assertThat(renewed).isFalse();
        assertThat(heartbeatService.isOwnershipLost(claimId)).isTrue();

        // Second renewal attempt should immediately return false without calling database again
        boolean secondAttempt = heartbeatService.renewClaimDirectly(claimId);
        assertThat(secondAttempt).isFalse();
        verify(repository, times(1)).renewClaimDatabaseTime(any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("Transient database exception increments failure counter but does not immediately lose ownership")
    void renewClaim_transientException_incrementsFailuresWithoutImmediateLoss() {
        heartbeatService.registerClaim(claimId, userId, idempotencyKey, claimOwner, claimGeneration, Duration.ofSeconds(60));

        when(repository.renewClaimDatabaseTime(eq(claimId), eq(claimOwner), eq(claimGeneration), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("Transient connection error"));

        boolean renewed = heartbeatService.renewClaimDirectly(claimId);

        assertThat(renewed).isFalse();
        assertThat(heartbeatService.getConsecutiveFailures(claimId)).isEqualTo(1);
        assertThat(heartbeatService.isOwnershipLost(claimId)).isFalse();
    }

    @Test
    @DisplayName("Repeated heartbeat failures exceeding threshold fail closed and mark ownership lost")
    void renewClaim_repeatedExceptions_failsClosedWhenThresholdReached() {
        heartbeatService.registerClaim(claimId, userId, idempotencyKey, claimOwner, claimGeneration, Duration.ofSeconds(60));

        when(repository.renewClaimDatabaseTime(eq(claimId), eq(claimOwner), eq(claimGeneration), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("Database unreachable"));

        // Attempt 1: failure 1/3 -> still not lost
        heartbeatService.renewClaimDirectly(claimId);
        assertThat(heartbeatService.isOwnershipLost(claimId)).isFalse();
        assertThat(heartbeatService.getConsecutiveFailures(claimId)).isEqualTo(1);

        // Attempt 2: failure 2/3 -> still not lost
        heartbeatService.renewClaimDirectly(claimId);
        assertThat(heartbeatService.isOwnershipLost(claimId)).isFalse();
        assertThat(heartbeatService.getConsecutiveFailures(claimId)).isEqualTo(2);

        // Attempt 3: failure 3/3 -> fails closed!
        heartbeatService.renewClaimDirectly(claimId);
        assertThat(heartbeatService.isOwnershipLost(claimId)).isTrue();
        assertThat(heartbeatService.getConsecutiveFailures(claimId)).isEqualTo(3);

        // Attempt 4: already lost, does not invoke repository
        boolean fourthAttempt = heartbeatService.renewClaimDirectly(claimId);
        assertThat(fourthAttempt).isFalse();
        verify(repository, times(3)).renewClaimDatabaseTime(any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("Recovery after transient failure resets consecutive failure counter")
    void renewClaim_successAfterTransientFailure_resetsCounter() {
        heartbeatService.registerClaim(claimId, userId, idempotencyKey, claimOwner, claimGeneration, Duration.ofSeconds(60));

        when(repository.renewClaimDatabaseTime(eq(claimId), eq(claimOwner), eq(claimGeneration), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("Temporary glitch"))
                .thenReturn(1);

        // Attempt 1 fails
        heartbeatService.renewClaimDirectly(claimId);
        assertThat(heartbeatService.getConsecutiveFailures(claimId)).isEqualTo(1);
        assertThat(heartbeatService.isOwnershipLost(claimId)).isFalse();

        // Attempt 2 succeeds -> counter reset
        boolean renewed = heartbeatService.renewClaimDirectly(claimId);
        assertThat(renewed).isTrue();
        assertThat(heartbeatService.getConsecutiveFailures(claimId)).isEqualTo(0);
        assertThat(heartbeatService.isOwnershipLost(claimId)).isFalse();
    }

    @Test
    @DisplayName("Ownership loss is observable via ClaimOwnershipContext in leader thread")
    void claimOwnershipContext_reflectsOwnershipLoss() {
        heartbeatService.registerClaim(claimId, userId, idempotencyKey, claimOwner, claimGeneration, Duration.ofSeconds(60));

        ClaimOwnershipContext ctx = new ClaimOwnershipContext(
                claimId, userId, idempotencyKey, claimOwner, claimGeneration, heartbeatService
        );
        ClaimOwnershipContext.set(ctx);

        assertThat(ClaimOwnershipContext.get().isOwnershipLost()).isFalse();

        // Trigger ownership loss via 0-row renewal
        when(repository.renewClaimDatabaseTime(eq(claimId), eq(claimOwner), eq(claimGeneration), anyLong()))
                .thenReturn(0);
        heartbeatService.renewClaimDirectly(claimId);

        assertThat(ClaimOwnershipContext.get().isOwnershipLost()).isTrue();
    }

    @Test
    @DisplayName("Scheduler shutdown cancels all active tasks and terminates executor")
    void shutdown_cancelsActiveTasksAndShutsDownScheduler() {
        heartbeatService.registerClaim(claimId, userId, idempotencyKey, claimOwner, claimGeneration, Duration.ofSeconds(60));
        assertThat(heartbeatService.getActiveClaimCount()).isEqualTo(1);

        heartbeatService.shutdown();

        assertThat(heartbeatService.getActiveClaimCount()).isEqualTo(0);
        assertThat(scheduler.isShutdown()).isTrue();
    }

    @Test
    @DisplayName("Deterministic timing: transient failure within safe lease window does not lose ownership")
    void timing_transientFailureWithinSafeLeaseWindow_doesNotLoseOwnership() {
        MutableClock clock = new MutableClock(java.time.Instant.parse("2026-11-12T12:00:00Z"));
        IdempotencyClaimHeartbeatService clockHeartbeatService = new IdempotencyClaimHeartbeatService(
                repository, transactionManager, scheduler, clock, 15_000L, 3, Duration.ofSeconds(15)
        );

        clockHeartbeatService.registerClaim(claimId, userId, idempotencyKey, claimOwner, claimGeneration, Duration.ofSeconds(60));
        assertThat(clockHeartbeatService.getSafeLeaseWindow(claimId)).isEqualTo(Duration.ofSeconds(45));

        // Advance 15 seconds (well within the 45s safe lease window)
        clock.advance(Duration.ofSeconds(15));
        when(repository.renewClaimDatabaseTime(eq(claimId), eq(claimOwner), eq(claimGeneration), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("Transient connection reset"));

        boolean renewed = clockHeartbeatService.renewClaimDirectly(claimId);

        assertThat(renewed).isFalse();
        assertThat(clockHeartbeatService.getConsecutiveFailures(claimId)).isEqualTo(1);
        assertThat(clockHeartbeatService.isOwnershipLost(claimId)).isFalse();
    }

    @Test
    @DisplayName("Deterministic timing: scheduler delay exceeding safe lease window marks ownership lost even with zero failures")
    void timing_schedulerDelay_marksOwnershipLostEvenWithZeroFailures() {
        MutableClock clock = new MutableClock(java.time.Instant.parse("2026-11-12T12:00:00Z"));
        IdempotencyClaimHeartbeatService clockHeartbeatService = new IdempotencyClaimHeartbeatService(
                repository, transactionManager, scheduler, clock, 15_000L, 3, Duration.ofSeconds(15)
        );

        clockHeartbeatService.registerClaim(claimId, userId, idempotencyKey, claimOwner, claimGeneration, Duration.ofSeconds(60));
        assertThat(clockHeartbeatService.isOwnershipLost(claimId)).isFalse();

        // Simulate 46 seconds elapsed without any renewal task executing (scheduler starvation / delay)
        clock.advance(Duration.ofSeconds(46));

        // Ownership must be marked lost immediately before PostgreSQL lease can plausibly expire
        assertThat(clockHeartbeatService.isOwnershipLost(claimId)).isTrue();
        assertThat(clockHeartbeatService.getConsecutiveFailures(claimId)).isEqualTo(0);
    }

    @Test
    @DisplayName("Deterministic timing: slow query or delayed task fails closed on first attempt if safe window exceeded")
    void timing_slowQueryOrDelayedTask_failsClosedOnFirstAttemptIfSafeWindowExceeded() {
        MutableClock clock = new MutableClock(java.time.Instant.parse("2026-11-12T12:00:00Z"));
        IdempotencyClaimHeartbeatService clockHeartbeatService = new IdempotencyClaimHeartbeatService(
                repository, transactionManager, scheduler, clock, 15_000L, 3, Duration.ofSeconds(15)
        );

        clockHeartbeatService.registerClaim(claimId, userId, idempotencyKey, claimOwner, claimGeneration, Duration.ofSeconds(60));

        // Simulate 46 seconds elapsed when task finally runs and throws exception
        clock.advance(Duration.ofSeconds(46));
        when(repository.renewClaimDatabaseTime(eq(claimId), eq(claimOwner), eq(claimGeneration), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("Query timeout after long delay"));

        boolean renewed = clockHeartbeatService.renewClaimDirectly(claimId);

        // Even though failures is only 1 (< 3), elapsed time exceeded safe lease window -> fails closed
        assertThat(renewed).isFalse();
        assertThat(clockHeartbeatService.getConsecutiveFailures(claimId)).isEqualTo(1);
        assertThat(clockHeartbeatService.isOwnershipLost(claimId)).isTrue();

        // Subsequent renewal attempt returns false without hitting repository
        boolean nextAttempt = clockHeartbeatService.renewClaimDirectly(claimId);
        assertThat(nextAttempt).isFalse();
        verify(repository, times(1)).renewClaimDatabaseTime(any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("Deterministic timing: successful renewal advances confirmed renewal time and extends safe lease window")
    void timing_successfulRenewalAdvancesConfirmedTime_extendsSafeWindow() {
        MutableClock clock = new MutableClock(java.time.Instant.parse("2026-11-12T12:00:00Z"));
        IdempotencyClaimHeartbeatService clockHeartbeatService = new IdempotencyClaimHeartbeatService(
                repository, transactionManager, scheduler, clock, 15_000L, 3, Duration.ofSeconds(15)
        );

        clockHeartbeatService.registerClaim(claimId, userId, idempotencyKey, claimOwner, claimGeneration, Duration.ofSeconds(60));
        java.time.Instant initialConfirmed = clockHeartbeatService.getLastConfirmedRenewalTime(claimId);
        assertThat(initialConfirmed).isEqualTo(clock.instant());

        // Advance 20 seconds and renew successfully
        clock.advance(Duration.ofSeconds(20));
        when(repository.renewClaimDatabaseTime(eq(claimId), eq(claimOwner), eq(claimGeneration), anyLong()))
                .thenReturn(1);

        boolean renewed = clockHeartbeatService.renewClaimDirectly(claimId);
        assertThat(renewed).isTrue();
        assertThat(clockHeartbeatService.getLastConfirmedRenewalTime(claimId)).isEqualTo(clock.instant());

        // Advance another 30 seconds (total 50s from start, but only 30s from last confirmed renewal)
        clock.advance(Duration.ofSeconds(30));
        when(repository.renewClaimDatabaseTime(eq(claimId), eq(claimOwner), eq(claimGeneration), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("Temporary glitch"));

        boolean secondRenewal = clockHeartbeatService.renewClaimDirectly(claimId);
        assertThat(secondRenewal).isFalse();
        // Since elapsed from confirmed renewal is 30s (< 45s safe window), ownership is NOT lost
        assertThat(clockHeartbeatService.isOwnershipLost(claimId)).isFalse();

        // Advance 16 more seconds (46s from confirmed renewal >= 45s safe window)
        clock.advance(Duration.ofSeconds(16));
        assertThat(clockHeartbeatService.isOwnershipLost(claimId)).isTrue();
    }

    @Test
    @DisplayName("Deterministic timing: once ownership is lost, no further database renewals or mutations permitted")
    void timing_onceOwnershipLost_noFurtherRenewalsPermitted() {
        MutableClock clock = new MutableClock(java.time.Instant.parse("2026-11-12T12:00:00Z"));
        IdempotencyClaimHeartbeatService clockHeartbeatService = new IdempotencyClaimHeartbeatService(
                repository, transactionManager, scheduler, clock, 15_000L, 3, Duration.ofSeconds(15)
        );

        clockHeartbeatService.registerClaim(claimId, userId, idempotencyKey, claimOwner, claimGeneration, Duration.ofSeconds(60));

        // Advance past safe lease window
        clock.advance(Duration.ofSeconds(46));
        assertThat(clockHeartbeatService.isOwnershipLost(claimId)).isTrue();

        // Any subsequent direct renewal must fail closed and never touch database
        boolean renewed = clockHeartbeatService.renewClaimDirectly(claimId);
        assertThat(renewed).isFalse();
        verify(repository, times(0)).renewClaimDatabaseTime(any(), any(), anyLong(), anyLong());
    }

    static class MutableClock extends java.time.Clock {
        private java.time.Instant currentInstant;
        private final java.time.ZoneId zone = java.time.ZoneId.of("UTC");

        MutableClock(java.time.Instant initialInstant) {
            this.currentInstant = initialInstant;
        }

        @Override
        public java.time.ZoneId getZone() {
            return zone;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return currentInstant;
        }

        public void advance(Duration duration) {
            this.currentInstant = this.currentInstant.plus(duration);
        }
    }
}
