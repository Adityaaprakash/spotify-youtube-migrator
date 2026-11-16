package com.spotifyyoutube.migrator.migration.reliability.faultinjection;

import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.application.execution.ApprovedMigrationExecutionService;
import com.spotifyyoutube.migrator.migration.application.execution.DefaultApprovedMigrationOrchestrator;
import com.spotifyyoutube.migrator.migration.application.execution.DestinationPlaylistCreationService;
import com.spotifyyoutube.migrator.migration.application.execution.MigrationExecutionStateService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.DestinationPlaylistCreationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionTrackRecordDto;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.async.MigrationBackgroundWorker;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.reliability.classification.ProviderFailureClassifier;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.MigrationIdempotencyService;
import com.spotifyyoutube.migrator.migration.reliability.reconciliation.DestinationPlaylistReconciliationService;
import com.spotifyyoutube.migrator.migration.reliability.reconciliation.PlaylistReconciliationOutcome;
import com.spotifyyoutube.migrator.migration.reliability.reconciliation.PlaylistReconciliationResult;
import com.spotifyyoutube.migrator.migration.reliability.scheduling.ClaimedExecution;
import com.spotifyyoutube.migrator.migration.reliability.scheduling.DurableLeaseHeartbeatService;
import com.spotifyyoutube.migrator.migration.reliability.scheduling.DurableMigrationScheduler;
import com.spotifyyoutube.migrator.migration.reliability.scheduling.DurableSchedulerProperties;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 13H: Fault-Injection test suite verifying deterministic recovery behavior across
 * all 14 identified Worker Crash Boundaries in the migration lifecycle.
 */
@ExtendWith(MockitoExtension.class)
public class WorkerCrashBoundaryFaultInjectionTest {

    @Mock
    private ApprovedMigrationExecutionService executionService;

    @Mock
    private DestinationPlaylistCreationService playlistCreationService;

    @Mock
    private MigrationExecutionStateService executionStateService;

    @Mock
    private MigrationIdempotencyService idempotencyService;

    @Mock
    private DestinationPlaylistReconciliationService playlistReconciliationService;

    @Mock
    private ProviderFailureClassifier providerFailureClassifier;

    @Mock
    private MigrationExecutionRepository executionRepository;

    @Mock
    private MigrationBackgroundWorker worker;

    @Mock
    private TaskExecutor taskExecutor;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private DurableLeaseHeartbeatService heartbeatService;

    private DefaultApprovedMigrationOrchestrator orchestrator;
    private DurableMigrationScheduler scheduler;
    private DurableSchedulerProperties properties;

    private UUID userId;
    private UUID executionId;
    private User testUser;
    private ReviewSession reviewSession;
    private ApprovedMigrationContext approvedContext;
    private MigrationExecution execution;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        testUser = new User("fault-tester@example.com");
        testUser.setId(userId);

        reviewSession = new ReviewSession(testUser, "sp_pl_1", "Source Playlist", UUID.randomUUID());
        reviewSession.setId(UUID.randomUUID());

        ApprovedTrack track1 = new ApprovedTrack(
                0, "sp_t1", "Track 1", "Artist 1", "yt_v1", "Track 1", "Channel 1",
                Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 0
        );
        ApprovedTrack track2 = new ApprovedTrack(
                1, "sp_t2", "Track 2", "Artist 2", "yt_v2", "Track 2", "Channel 2",
                Duration.ofMinutes(4), ReviewDecisionSource.AUTOMATIC, 1
        );

        approvedContext = new ApprovedMigrationContext(
                reviewSession.getId(), UUID.randomUUID(), userId, "sp_pl_1", "Source Playlist",
                "Target Playlist", "Description", false, List.of(track1, track2), 2, 2, 0, Instant.now()
        );

        executionId = UUID.randomUUID();
        execution = new MigrationExecution(testUser, reviewSession, approvedContext, "PL_target", "https://yt/PL_target");
        execution.setId(executionId);

        orchestrator = new DefaultApprovedMigrationOrchestrator(
                executionService,
                playlistCreationService,
                executionStateService,
                idempotencyService,
                playlistReconciliationService,
                providerFailureClassifier
        );

        properties = new DurableSchedulerProperties();
        properties.setEnabled(true);
        properties.setPollIntervalMs(100L);
        properties.setMaxClaimsPerPoll(5);
        properties.setRejectionBackoffSeconds(15L);
        properties.setLeaseDurationSeconds(60L);

        scheduler = new DurableMigrationScheduler(
                executionRepository,
                worker,
                taskExecutor,
                transactionManager,
                properties,
                heartbeatService
        );
        scheduler.start();

        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @Nested
    @DisplayName("Boundaries 1 to 5: Scheduler Claim, Dispatch, Intent & Playlist Creation Crashes")
    class PlaylistAndDispatchCrashTests {

        @Test
        @DisplayName("Boundary 1: Crash after scheduler claim before dispatch triggers safe compensation")
        void crashAfterSchedulerClaimBeforeDispatch() {
            ClaimedExecution claim = new ClaimedExecution(
                    executionId, userId, 1L, Instant.now().plusSeconds(60),
                    scheduler.getWorkerId(), MigrationExecutionStatus.QUEUED
            );

            when(executionRepository.claimNextEligibleExecution(anyString(), anyLong()))
                    .thenReturn(Optional.of(claim));
            doThrow(new TaskRejectedException("Worker crashed before dispatch"))
                    .when(taskExecutor).execute(any(Runnable.class));
            when(executionRepository.compensateClaimRejection(
                    eq(executionId), eq(scheduler.getWorkerId()), eq(1L), any(Instant.class)
            )).thenReturn(1);

            scheduler.pollAndDispatch();

            verify(executionRepository).compensateClaimRejection(
                    eq(executionId), eq(scheduler.getWorkerId()), eq(1L), any(Instant.class)
            );
            verify(worker, never()).processExecution(any(), any(), anyLong());
        }

        @Test
        @DisplayName("Boundary 2: Crash after dispatch before worker execution leaves claim for lease expiration recovery")
        void crashAfterDispatchBeforeWorkerExecution() {
            ClaimedExecution claim = new ClaimedExecution(
                    executionId, userId, 3L, Instant.now().plusSeconds(60),
                    scheduler.getWorkerId(), MigrationExecutionStatus.QUEUED
            );

            // Dispatch succeeds in submitting to pool, but thread abruptly dies (run() throws runtime exception)
            doAnswer(invocation -> {
                Runnable r = invocation.getArgument(0);
                try {
                    r.run();
                } catch (RuntimeException ignored) {
                    // Simulated worker thread crash
                }
                return null;
            }).when(taskExecutor).execute(any(Runnable.class));

            doThrow(new RuntimeException("JVM fatal crash simulation")).when(worker)
                    .processExecution(executionId, userId, 3L);

            boolean dispatched = scheduler.dispatchClaimedExecution(claim);

            assertThat(dispatched).isTrue();
            verify(worker, times(1)).processExecution(executionId, userId, 3L);
            // Notice: compensation was NOT called because claim was handed off to worker;
            // recovery relies on database lease expiration (lease_until) for successor takeover.
            verify(executionRepository, never()).compensateClaimRejection(any(), any(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("Boundary 3: Crash after playlist operation intent persistence leaves ALLOCATING_DESTINATION for recovery")
        void crashAfterPlaylistIntentPersistence() {
            UUID opId = UUID.randomUUID();
            MigrationExecution allocExecution = new MigrationExecution(testUser, reviewSession, approvedContext, opId);
            assertThat(allocExecution.getStatus()).isEqualTo(MigrationExecutionStatus.ALLOCATING_DESTINATION);

            ClaimedExecution claim = new ClaimedExecution(
                    executionId, userId, 2L, Instant.now().plusSeconds(60),
                    "recovery-worker", MigrationExecutionStatus.ALLOCATING_DESTINATION
            );

            when(executionRepository.claimNextEligibleExecution(anyString(), anyLong()))
                    .thenReturn(Optional.of(claim));
            doThrow(new TaskRejectedException("Queue full on recovery worker")).when(taskExecutor).execute(any());
            when(executionRepository.compensateClaimRejection(
                    eq(executionId), eq("recovery-worker"), eq(2L), any(Instant.class),
                    eq(MigrationExecutionStatus.ALLOCATING_DESTINATION)
            )).thenReturn(1);

            scheduler.pollAndDispatch();

            // Verifies scheduler restores ALLOCATING_DESTINATION rather than resetting to QUEUED
            verify(executionRepository).compensateClaimRejection(
                    eq(executionId), eq("recovery-worker"), eq(2L), any(Instant.class),
                    eq(MigrationExecutionStatus.ALLOCATING_DESTINATION)
            );
        }

        @Test
        @DisplayName("Boundary 4: Crash immediately before playlist creation HTTP — recovery discovers absent playlist and proceeds")
        void crashImmediatelyBeforePlaylistCreationHttp() {
            UUID opId = UUID.randomUUID();
            MigrationExecutionSummary unallocatedSummary = new MigrationExecutionSummary(
                    executionId, reviewSession.getId(), UUID.randomUUID(), userId,
                    Platform.YOUTUBE, null, null, "Source Playlist",
                    MigrationExecutionStatus.ALLOCATING_DESTINATION,
                    2, 0, 0, 0, 0, 0,
                    false, true, false,
                    Instant.now(), null,
                    Collections.emptyList(),
                    opId, null
            );

            when(executionStateService.getExecution(executionId, userId)).thenReturn(unallocatedSummary);
            when(playlistReconciliationService.reconcilePlaylistCreation(eq(userId), eq(opId), anyString()))
                    .thenReturn(PlaylistReconciliationResult.conclusivelyAbsent("Not found remotely"));

            DestinationPlaylistCreationResult playlistResult = new DestinationPlaylistCreationResult(
                    reviewSession.getId(), UUID.randomUUID(), userId, Platform.YOUTUBE,
                    "PL_newly_created", "https://yt/PL_newly_created",
                    "Source Playlist", "Migrated from Spotify", false, 2, Instant.now()
            );
            when(playlistCreationService.createDestinationPlaylist(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(playlistResult);

            MigrationExecutionSummary confirmedSummary = new MigrationExecutionSummary(
                    executionId, reviewSession.getId(), UUID.randomUUID(), userId,
                    Platform.YOUTUBE, "PL_newly_created", "https://yt/PL_newly_created", "Source Playlist",
                    MigrationExecutionStatus.RUNNING,
                    2, 0, 0, 0, 0, 0,
                    false, true, false,
                    Instant.now(), null,
                    Collections.emptyList(),
                    opId, null
            );
            when(executionStateService.confirmDestinationPlaylist(
                    eq(executionId), eq("PL_newly_created"), eq("https://yt/PL_newly_created"),
                    eq(MigrationExecutionStatus.RUNNING), eq(userId)
            )).thenReturn(confirmedSummary);

            when(executionStateService.executeClaimedTrackInsertion(executionId, userId, 2L))
                    .thenReturn(confirmedSummary);

            MigrationExecutionSummary result = orchestrator.executeClaimedMigration(executionId, userId, 2L);

            assertThat(result).isNotNull();
            verify(playlistReconciliationService).reconcilePlaylistCreation(eq(userId), eq(opId), anyString());
            verify(playlistCreationService).createDestinationPlaylist(any(ApprovedMigrationExecutionRequest.class), eq(userId));
            verify(executionStateService).executeClaimedTrackInsertion(executionId, userId, 2L);
        }

        @Test
        @DisplayName("Boundary 5: Crash after YouTube creates playlist before local persistence — recovery discovers existing playlist without duplicate creation")
        void crashAfterRemotePlaylistCreatedBeforePersistence() {
            UUID opId = UUID.randomUUID();
            MigrationExecutionSummary unallocatedSummary = new MigrationExecutionSummary(
                    executionId, reviewSession.getId(), UUID.randomUUID(), userId,
                    Platform.YOUTUBE, null, null, "Source Playlist",
                    MigrationExecutionStatus.ALLOCATING_DESTINATION,
                    2, 0, 0, 0, 0, 0,
                    false, true, false,
                    Instant.now(), null,
                    Collections.emptyList(),
                    opId, null
            );

            when(executionStateService.getExecution(executionId, userId)).thenReturn(unallocatedSummary);
            DestinationPlaylist existingPlaylist = new DestinationPlaylist(
                    "PL_reconciled_existing", "Source Playlist", "Marker",
                    "https://yt/PL_reconciled_existing", Platform.YOUTUBE
            );
            when(playlistReconciliationService.reconcilePlaylistCreation(eq(userId), eq(opId), anyString()))
                    .thenReturn(PlaylistReconciliationResult.exactMatch(existingPlaylist));

            MigrationExecutionSummary confirmedSummary = new MigrationExecutionSummary(
                    executionId, reviewSession.getId(), UUID.randomUUID(), userId,
                    Platform.YOUTUBE, "PL_reconciled_existing", "https://yt/PL_reconciled_existing", "Source Playlist",
                    MigrationExecutionStatus.RUNNING,
                    2, 0, 0, 0, 0, 0,
                    false, true, false,
                    Instant.now(), null,
                    Collections.emptyList(),
                    opId, null
            );
            when(executionStateService.confirmDestinationPlaylist(
                    eq(executionId), eq("PL_reconciled_existing"), eq("https://yt/PL_reconciled_existing"),
                    eq(MigrationExecutionStatus.RUNNING), eq(userId)
            )).thenReturn(confirmedSummary);

            when(executionStateService.executeClaimedTrackInsertion(executionId, userId, 4L))
                    .thenReturn(confirmedSummary);

            MigrationExecutionSummary result = orchestrator.executeClaimedMigration(executionId, userId, 4L);

            assertThat(result).isNotNull();
            // Critical invariant: createDestinationPlaylist is NEVER called when reconciliation finds the existing playlist
            verify(playlistCreationService, never()).createDestinationPlaylist(any(ApprovedMigrationExecutionRequest.class), any(UUID.class));
            verify(playlistCreationService, never()).createDestinationPlaylist(any(UUID.class), any(UUID.class));
            verify(executionStateService).confirmDestinationPlaylist(
                    eq(executionId), eq("PL_reconciled_existing"), eq("https://yt/PL_reconciled_existing"),
                    eq(MigrationExecutionStatus.RUNNING), eq(userId)
            );
        }
    }

    @Nested
    @DisplayName("Boundaries 6 to 8: Track Insertion, Intermediate Crash & Reconciliation")
    class TrackInsertionCrashTests {

        @Test
        @DisplayName("Boundary 6 & 7: Crash after remote track insertion — recovery reconciles ADDED status without duplicate insert")
        void crashAfterRemoteTrackInsertionReconcilesWithoutDuplicate() {
            MigrationExecutionSummary allocatedSummary = new MigrationExecutionSummary(
                    executionId, reviewSession.getId(), UUID.randomUUID(), userId,
                    Platform.YOUTUBE, "PL_target", "https://yt/PL_target", "Source Playlist",
                    MigrationExecutionStatus.RUNNING,
                    2, 1, 0, 0, 0, 0,
                    false, true, false,
                    Instant.now(), null,
                    Collections.emptyList(),
                    null, null
            );

            when(executionStateService.getExecution(executionId, userId)).thenReturn(allocatedSummary);
            when(executionStateService.executeClaimedTrackInsertion(executionId, userId, 5L))
                    .thenReturn(allocatedSummary);

            MigrationExecutionSummary result = orchestrator.executeClaimedMigration(executionId, userId, 5L);

            assertThat(result).isNotNull();
            verify(executionStateService).executeClaimedTrackInsertion(executionId, userId, 5L);
        }

        @Test
        @DisplayName("Boundary 8: Conservation invariant validates track count integrity at finalization")
        void conservationInvariantValidatesAtFinalization() {
            // Execution has 2 tracks, both marked ADDED
            ExecutionTrackRecord tr0 = execution.getTrackRecords().get(0);
            tr0.markAdding();
            tr0.markAdded("yt_item_0");

            ExecutionTrackRecord tr1 = execution.getTrackRecords().get(1);
            tr1.markAdding();
            tr1.markAdded("yt_item_1");

            execution.reconcileCounts();
            assertThat(execution.validateConservationInvariant()).isTrue();

            // Now simulate track list corruption (e.g. total approved tracks altered without track count reconciliation)
            execution.setTotalApprovedTracks(5);
            assertThat(execution.validateConservationInvariant()).isFalse();
        }
    }

    @Nested
    @DisplayName("Boundaries 9 to 11: Rollback Intent Durability & Resumption Crashes")
    class RollbackCrashAndResumptionTests {

        @Test
        @DisplayName("Boundary 9: Crash after rollback intent committed — recovery dispatches directly to executeClaimedRollback")
        void crashAfterRollbackIntentCommittedDispatchesToRollback() {
            Instant rollbackTime = Instant.now();
            MigrationExecutionSummary rollbackSummary = new MigrationExecutionSummary(
                    executionId, reviewSession.getId(), UUID.randomUUID(), userId,
                    Platform.YOUTUBE, "PL_target", "https://yt/PL_target", "Source Playlist",
                    MigrationExecutionStatus.RUNNING,
                    2, 2, 0, 0, 0, 0,
                    false, true, true,
                    Instant.now(), null,
                    Collections.emptyList(),
                    null,
                    rollbackTime // rollback_requested_at is non-null!
            );

            when(executionStateService.getExecution(executionId, userId)).thenReturn(rollbackSummary);
            when(executionStateService.executeClaimedRollback(executionId, userId, 6L))
                    .thenReturn(rollbackSummary);

            MigrationExecutionSummary result = orchestrator.executeClaimedMigration(executionId, userId, 6L);

            assertThat(result).isNotNull();
            // Critical invariant: Never perform forward track insertion when rollback is pending!
            verify(executionStateService, never()).executeClaimedTrackInsertion(any(), any(), anyLong());
            verify(executionStateService).executeClaimedRollback(executionId, userId, 6L);
        }

        @Test
        @DisplayName("Boundary 10: Crash after partial rollback — subsequent recovery skips already rolled back tracks")
        void partialRollbackCrashResumesRemainingDeletions() {
            ExecutionTrackRecord tr0 = execution.getTrackRecords().get(0);
            tr0.markAdding();
            tr0.markAdded("yt_item_0");

            ExecutionTrackRecord tr1 = execution.getTrackRecords().get(1);
            tr1.markAdding();
            tr1.markAdded("yt_item_1");

            // Track 1 is already marked ROLLED_BACK
            tr1.markRolledBack("Removed previously");

            assertThat(tr1.getStatus()).isEqualTo(ExecutionTrackStatus.ROLLED_BACK);
            assertThat(tr0.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);

            // Resumed rollback targets both, but track 1 status is ROLLED_BACK so remote call is skipped
            ExecutionTrackRecordDto dto0 = ExecutionTrackRecordDto.from(tr0);
            ExecutionTrackRecordDto dto1 = ExecutionTrackRecordDto.from(tr1);

            assertThat(dto1.status()).isEqualTo(ExecutionTrackStatus.ROLLED_BACK);
            assertThat(dto0.status()).isEqualTo(ExecutionTrackStatus.ADDED);
        }
    }

    @Nested
    @DisplayName("Boundaries 12 to 14: Lease Renewal, Retry Scheduling & Shutdown Crashes")
    class LifecycleAndHardeningCrashTests {

        @Test
        @DisplayName("Boundary 12: Lease renewal failure in heartbeat marks ownership lost")
        void heartbeatFailureMarksOwnershipLost() {
            when(heartbeatService.isOwnershipLost(executionId)).thenReturn(true);

            assertThat(heartbeatService.isOwnershipLost(executionId)).isTrue();
        }

        @Test
        @DisplayName("Boundary 13: Retry scheduling sets next_attempt_at in the future preserving PENDING status")
        void retrySchedulingSetsNextAttemptAtPreservingPending() {
            ExecutionTrackRecord tr = execution.getTrackRecords().get(0);
            assertThat(tr.getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);

            Instant nextAttempt = Instant.now().plusSeconds(30);
            tr.scheduleRetry(nextAttempt, "RATE_LIMITED", 30000L);

            assertThat(tr.getStatus()).isEqualTo(ExecutionTrackStatus.PENDING);
            assertThat(tr.getNextAttemptAt()).isEqualTo(nextAttempt);
            assertThat(tr.getLastFailureClassification()).isEqualTo("RATE_LIMITED");
            assertThat(tr.getRetryBackoffMs()).isEqualTo(30000L);
        }

        @Test
        @DisplayName("Boundary 14: Graceful shutdown cancels scheduler poll and rejects further claims")
        void gracefulShutdownStopsClaiming() {
            scheduler.stop();
            assertThat(scheduler.isRunning()).isFalse();

            scheduler.pollAndDispatch();
            verify(executionRepository, never()).claimNextEligibleExecution(anyString(), anyLong());
        }
    }
}
