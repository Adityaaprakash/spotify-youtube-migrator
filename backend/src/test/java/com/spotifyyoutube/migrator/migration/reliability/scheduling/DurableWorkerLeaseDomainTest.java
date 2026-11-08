package com.spotifyyoutube.migrator.migration.reliability.scheduling;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Phase 13D-A: Durable Worker Lease & Generational Fencing Domain Invariants")
class DurableWorkerLeaseDomainTest {

    private User testUser;
    private ReviewSession testSession;
    private ApprovedMigrationContext sampleContext;
    private MigrationExecution execution;

    @BeforeEach
    void setUp() {
        testUser = new User("worker-test@example.com");
        testUser.setId(UUID.randomUUID());

        testSession = new ReviewSession(testUser, "sp_playlist_lease", "Lease Test Playlist", UUID.randomUUID());
        testSession.setId(UUID.randomUUID());

        ApprovedTrack track0 = new ApprovedTrack(
                0, "sp_t0", "Track 0", "Artist A",
                "yt_t0", "Track 0 (Video)", "Channel A",
                Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 0
        );
        ApprovedTrack track1 = new ApprovedTrack(
                1, "sp_t1", "Track 1", "Artist B",
                "yt_t1", "Track 1 (Video)", "Channel B",
                Duration.ofMinutes(4), ReviewDecisionSource.USER_SELECTED, 1
        );

        sampleContext = new ApprovedMigrationContext(
                testSession.getId(), UUID.randomUUID(), testUser.getId(),
                "sp_playlist_lease", "Lease Test Playlist", "Target Playlist",
                "Description", false, List.of(track0, track1),
                2, 2, 0, Instant.now()
        );

        execution = new MigrationExecution(
                testUser, testSession, sampleContext,
                "PL_lease_test_123", "https://music.youtube.com/playlist?list=PL_lease_test_123",
                MigrationExecutionStatus.QUEUED
        );
    }

    @Nested
    @DisplayName("Default Entity Field Invariants")
    class DefaultFieldTests {

        @Test
        @DisplayName("Newly created execution has zero lease generation and null lease metadata")
        void executionDefaults() {
            assertThat(execution.getLeaseGeneration()).isEqualTo(0L);
            assertThat(execution.getLeaseOwner()).isNull();
            assertThat(execution.getLeaseUntil()).isNull();
            assertThat(execution.getHeartbeatAt()).isNull();
            assertThat(execution.getNextAttemptAt()).isNull();
            assertThat(execution.isLeaseActive(Instant.now())).isFalse();
            assertThat(execution.isLeaseExpired(Instant.now())).isFalse();
        }

        @Test
        @DisplayName("Newly created track record has maxAttempts default 3 and null retry metadata")
        void trackRecordDefaults() {
            ExecutionTrackRecord track = execution.getTrackRecords().get(0);
            assertThat(track.getMaxAttempts()).isEqualTo(3);
            assertThat(track.getNextAttemptAt()).isNull();
            assertThat(track.getLastFailureClassification()).isNull();
            assertThat(track.getRetryBackoffMs()).isNull();
            assertThat(track.isDue(Instant.now())).isTrue();
            assertThat(track.isDeferred(Instant.now())).isFalse();
            assertThat(track.hasRemainingAttempts()).isTrue();
        }
    }

    @Nested
    @DisplayName("Lease Acquisition & Generation Monotonicity")
    class LeaseAcquisitionTests {

        @Test
        @DisplayName("Initial claim increments generation from 0 to 1 and assigns owner and timestamps")
        void initialClaim() {
            Instant now = Instant.parse("2026-11-07T12:00:00Z");
            Instant leaseUntil = now.plusSeconds(60);

            long generation = execution.acquireLease("worker-node-1", leaseUntil, now);

            assertThat(generation).isEqualTo(1L);
            assertThat(execution.getLeaseGeneration()).isEqualTo(1L);
            assertThat(execution.getLeaseOwner()).isEqualTo("worker-node-1");
            assertThat(execution.getLeaseUntil()).isEqualTo(leaseUntil);
            assertThat(execution.getHeartbeatAt()).isEqualTo(now);
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
            assertThat(execution.isLeaseActive(now)).isTrue();
            assertThat(execution.isLeaseExpired(now)).isFalse();
        }

        @Test
        @DisplayName("Reclaiming an expired lease increments generation monotonically")
        void reclaimExpiredLeaseIncrementsGeneration() {
            Instant t1 = Instant.parse("2026-11-07T12:00:00Z");
            execution.acquireLease("worker-1", t1.plusSeconds(60), t1);
            assertThat(execution.getLeaseGeneration()).isEqualTo(1L);

            Instant t2 = t1.plusSeconds(61); // lease expired
            assertThat(execution.isLeaseExpired(t2)).isTrue();

            long gen2 = execution.acquireLease("worker-2", t2.plusSeconds(60), t2);
            assertThat(gen2).isEqualTo(2L);
            assertThat(execution.getLeaseOwner()).isEqualTo("worker-2");
            assertThat(execution.getLeaseGeneration()).isEqualTo(2L);
        }

        @Test
        @DisplayName("Acquiring lease while an active unexpired lease is held throws InvalidStateException")
        void competingClaimOnActiveLeaseRejected() {
            Instant now = Instant.parse("2026-11-07T12:00:00Z");
            execution.acquireLease("worker-1", now.plusSeconds(60), now);

            Instant competingNow = now.plusSeconds(10);
            assertThatThrownBy(() -> execution.acquireLease("worker-2", competingNow.plusSeconds(60), competingNow))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("active lease held by worker 'worker-1'");
        }

        @Test
        @DisplayName("Acquiring lease rejects blank worker ID and null expiration")
        void invalidAcquisitionArguments() {
            Instant now = Instant.now();
            assertThatThrownBy(() -> execution.acquireLease(null, now.plusSeconds(60), now))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> execution.acquireLease("   ", now.plusSeconds(60), now))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> execution.acquireLease("worker-1", null, now))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Heartbeat Renewal & Generational Fencing")
    class HeartbeatFencingTests {

        @Test
        @DisplayName("Current lease owner with matching generation can renew heartbeat")
        void successfulHeartbeatRenewal() {
            Instant t0 = Instant.parse("2026-11-07T12:00:00Z");
            long gen = execution.acquireLease("worker-1", t0.plusSeconds(60), t0);

            Instant t1 = t0.plusSeconds(15);
            Instant newLeaseUntil = t1.plusSeconds(60);
            execution.renewHeartbeat("worker-1", gen, newLeaseUntil, t1);

            assertThat(execution.getHeartbeatAt()).isEqualTo(t1);
            assertThat(execution.getLeaseUntil()).isEqualTo(newLeaseUntil);
            assertThat(execution.getLeaseGeneration()).isEqualTo(gen);
        }

        @Test
        @DisplayName("Zombie worker holding old generation cannot renew heartbeat")
        void staleGenerationHeartbeatRenewalRejected() {
            Instant t0 = Instant.parse("2026-11-07T12:00:00Z");
            long gen1 = execution.acquireLease("worker-1", t0.plusSeconds(60), t0);

            // Simulate lease expiration and re-claim by worker-2
            Instant t2 = t0.plusSeconds(70);
            long gen2 = execution.acquireLease("worker-2", t2.plusSeconds(60), t2);
            assertThat(gen2).isEqualTo(2L);

            // Worker-1 wakes up from pause and attempts renewal with gen1
            assertThatThrownBy(() -> execution.renewHeartbeat("worker-1", gen1, t2.plusSeconds(60), t2))
                    .isInstanceOf(StaleLeaseOwnershipException.class)
                    .hasMessageContaining("is not the current owner");
        }

        @Test
        @DisplayName("Mismatched worker ID cannot renew heartbeat even with current generation number")
        void mismatchedWorkerIdRenewalRejected() {
            Instant t0 = Instant.parse("2026-11-07T12:00:00Z");
            long gen = execution.acquireLease("worker-1", t0.plusSeconds(60), t0);

            assertThatThrownBy(() -> execution.renewHeartbeat("imposter-worker", gen, t0.plusSeconds(60), t0))
                    .isInstanceOf(StaleLeaseOwnershipException.class);
        }
    }

    @Nested
    @DisplayName("Lease Release & Retry Scheduling")
    class LeaseReleaseTests {

        @Test
        @DisplayName("Active owner can cooperatively release lease on completion")
        void successfulLeaseRelease() {
            Instant t0 = Instant.parse("2026-11-07T12:00:00Z");
            long gen = execution.acquireLease("worker-1", t0.plusSeconds(60), t0);

            execution.releaseLease("worker-1", gen);
            assertThat(execution.getLeaseOwner()).isNull();
            assertThat(execution.getLeaseUntil()).isNull();
            assertThat(execution.getLeaseGeneration()).isEqualTo(gen);
        }

        @Test
        @DisplayName("Stale worker cannot release a lease owned by a newer generation")
        void staleLeaseReleaseRejected() {
            Instant t0 = Instant.parse("2026-11-07T12:00:00Z");
            long gen1 = execution.acquireLease("worker-1", t0.plusSeconds(60), t0);

            Instant t1 = t0.plusSeconds(70);
            execution.acquireLease("worker-2", t1.plusSeconds(60), t1);

            assertThatThrownBy(() -> execution.releaseLease("worker-1", gen1))
                    .isInstanceOf(StaleLeaseOwnershipException.class);
        }

        @Test
        @DisplayName("Active owner can release lease and transition execution to QUEUED with next_attempt_at")
        void releaseLeaseAndScheduleRetry() {
            Instant t0 = Instant.parse("2026-11-07T12:00:00Z");
            long gen = execution.acquireLease("worker-1", t0.plusSeconds(60), t0);

            Instant retryTime = t0.plusSeconds(30);
            execution.releaseLeaseAndScheduleRetry("worker-1", gen, retryTime);

            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.QUEUED);
            assertThat(execution.getLeaseOwner()).isNull();
            assertThat(execution.getLeaseUntil()).isNull();
            assertThat(execution.getNextAttemptAt()).isEqualTo(retryTime);
            assertThat(execution.getLeaseGeneration()).isEqualTo(gen);
        }

        @Test
        @DisplayName("Releasing lease while tracks are in-flight (ADDING or ROLLING_BACK) throws InvalidStateException")
        void releaseLeaseBlockedWhenTracksInFlight() {
            Instant t0 = Instant.parse("2026-11-07T12:00:00Z");
            long gen = execution.acquireLease("worker-1", t0.plusSeconds(60), t0);

            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            assertThat(execution.hasActiveInFlightTracks()).isTrue();

            assertThatThrownBy(() -> execution.releaseLease("worker-1", gen))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("tracks are actively in-flight");

            assertThatThrownBy(() -> execution.releaseLeaseAndScheduleRetry("worker-1", gen, t0.plusSeconds(30)))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("tracks are actively in-flight");

            // Settling track allows safe release
            track0.scheduleRetry(t0.plusSeconds(30), "RATE_LIMITED", 30000L);
            assertThat(execution.hasActiveInFlightTracks()).isFalse();

            execution.releaseLeaseAndScheduleRetry("worker-1", gen, t0.plusSeconds(30));
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.QUEUED);
        }

        @Test
        @DisplayName("getEarliestDeferredRetry returns minimum retry timestamp among deferred pending tracks")
        void earliestDeferredRetryCalculation() {
            Instant t0 = Instant.parse("2026-11-07T12:00:00Z");
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            ExecutionTrackRecord track1 = execution.getTrackRecords().get(1);

            assertThat(execution.getEarliestDeferredRetry()).isEmpty();

            track0.scheduleRetry(t0.plusSeconds(45), "RATE_LIMITED", 45000L);
            track1.scheduleRetry(t0.plusSeconds(15), "TRANSIENT_SERVER_ERROR", 15000L);

            assertThat(execution.getEarliestDeferredRetry()).isPresent();
            assertThat(execution.getEarliestDeferredRetry().get()).isEqualTo(t0.plusSeconds(15));
        }
    }

    @Nested
    @DisplayName("Track-Level Retry Scheduling Invariants")
    class TrackRetryTests {

        @Test
        @DisplayName("Scheduling retry resets track status to PENDING, sets retry metadata, and clears startedAt")
        void scheduleTrackRetry() {
            ExecutionTrackRecord track = execution.getTrackRecords().get(0);
            track.markAdding();
            assertThat(track.getStatus()).isEqualTo(ExecutionTrackStatus.ADDING);
            assertThat(track.getAttemptCount()).isEqualTo(1);
            assertThat(track.getStartedAt()).isNotNull();

            Instant nextAttempt = Instant.parse("2026-11-07T12:00:30Z");
            track.scheduleRetry(nextAttempt, "RATE_LIMITED", 30000L);

            assertThat(track.getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);
            assertThat(track.getNextAttemptAt()).isEqualTo(nextAttempt);
            assertThat(track.getLastFailureClassification()).isEqualTo("RATE_LIMITED");
            assertThat(track.getRetryBackoffMs()).isEqualTo(30000L);
            assertThat(track.getStartedAt()).isNull();
            assertThat(track.getAttemptCount()).isEqualTo(1); // attempt count preserved
        }

        @Test
        @DisplayName("Track due evaluation correctly distinguishes past/present from future timestamps")
        void trackDueAndDeferredEvaluation() {
            ExecutionTrackRecord track = execution.getTrackRecords().get(0);
            Instant scheduledTime = Instant.parse("2026-11-07T12:00:30Z");
            track.scheduleRetry(scheduledTime, "TRANSIENT_SERVER_ERROR", 30000L);

            // Before scheduled time: deferred, not due
            Instant before = Instant.parse("2026-11-07T12:00:15Z");
            assertThat(track.isDue(before)).isFalse();
            assertThat(track.isDeferred(before)).isTrue();

            // At scheduled time: due, not deferred
            assertThat(track.isDue(scheduledTime)).isTrue();
            assertThat(track.isDeferred(scheduledTime)).isFalse();

            // After scheduled time: due, not deferred
            Instant after = Instant.parse("2026-11-07T12:00:45Z");
            assertThat(track.isDue(after)).isTrue();
            assertThat(track.isDeferred(after)).isFalse();
        }

        @Test
        @DisplayName("Budget exhaustion check accurately evaluates attempt count against maxAttempts")
        void attemptBudgetEvaluation() {
            ExecutionTrackRecord track = execution.getTrackRecords().get(0);
            track.setMaxAttempts(3);

            track.markAdding(); // attempt 1
            assertThat(track.hasRemainingAttempts()).isTrue();

            track.scheduleRetry(Instant.now().plusSeconds(10), "RATE_LIMITED", 10000L);
            track.markAdding(); // attempt 2
            assertThat(track.hasRemainingAttempts()).isTrue();

            track.scheduleRetry(Instant.now().plusSeconds(20), "RATE_LIMITED", 20000L);
            track.markAdding(); // attempt 3
            assertThat(track.hasRemainingAttempts()).isFalse(); // 3 attempts consumed out of 3 max
        }
    }
}
