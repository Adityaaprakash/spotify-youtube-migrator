package com.spotifyyoutube.migrator.migration.reliability.recovery;

import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.application.execution.ApprovedMigrationExecutionService;
import com.spotifyyoutube.migrator.migration.application.execution.DefaultApprovedMigrationOrchestrator;
import com.spotifyyoutube.migrator.migration.application.execution.DestinationPlaylistCreationService;
import com.spotifyyoutube.migrator.migration.application.execution.MigrationExecutionStateService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
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
import com.spotifyyoutube.migrator.migration.reliability.scheduling.DurableMigrationScheduler;
import com.spotifyyoutube.migrator.migration.reliability.scheduling.DurableSchedulerProperties;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 13G: Recovery & Resume Workflow Unit Tests")
class Phase13GRecoveryWorkflowTest {

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

    private DefaultApprovedMigrationOrchestrator orchestrator;
    private DurableMigrationScheduler scheduler;

    private final UUID userId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        orchestrator = new DefaultApprovedMigrationOrchestrator(
                executionService,
                playlistCreationService,
                executionStateService,
                idempotencyService,
                playlistReconciliationService,
                providerFailureClassifier
        );

        DurableSchedulerProperties properties = new DurableSchedulerProperties();
        properties.setEnabled(true);
        properties.setLeaseDurationSeconds(60L);
        properties.setRejectionBackoffSeconds(15L);
        properties.setMaxClaimsPerPoll(2);
        properties.setWorkerIdPrefix("test-worker-");

        scheduler = new DurableMigrationScheduler(
                executionRepository,
                worker,
                taskExecutor,
                transactionManager,
                properties
        );
        scheduler.start();
    }

    private void stubTransactionManager() {
        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(txStatus);
    }

    @Test
    @DisplayName("Track count conservation invariant validates exact count")
    void trackConservationInvariantValidation() {
        User user = new User("user@example.com");
        user.setId(UUID.randomUUID());
        ReviewSession session = new ReviewSession(user, "sp_1", "Source Playlist", UUID.randomUUID());
        session.setId(UUID.randomUUID());
        ApprovedTrack t1 = new ApprovedTrack(
                0, "sp_t1", "Song1", "Artist1", "yt_v1", "Title1", "Ch1",
                Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 0
        );
        ApprovedTrack t2 = new ApprovedTrack(
                1, "sp_t2", "Song2", "Artist2", "yt_v2", "Title2", "Ch2",
                Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 1
        );

        ApprovedMigrationContext context = new ApprovedMigrationContext(
                session.getId(), UUID.randomUUID(), user.getId(),
                "sp_1", "Source Playlist", "Target Playlist", "Desc", false,
                List.of(t1, t2), 2, 2, 0, Instant.now()
        );

        MigrationExecution execution = new MigrationExecution(
                user, session, context, "PL_123", "https://youtube.com/playlist?list=PL_123"
        );

        // Initially both tracks are PENDING -> total 2 approved -> invariant holds
        assertThat(execution.validateConservationInvariant()).isTrue();

        // Mutate one track from PENDING -> ADDING -> ADDED
        execution.getTrackRecords().get(0).markAdding();
        execution.getTrackRecords().get(0).markAdded("yt_item_1");
        assertThat(execution.validateConservationInvariant()).isTrue();

        // Invariant fails if totalApprovedTracks does not match actual track count
        execution.setTotalApprovedTracks(3);
        assertThat(execution.validateConservationInvariant()).isFalse();
    }

    @Test
    @DisplayName("Orchestrator resumes claimed rollback when rollback intent is present")
    void orchestratorResumesClaimedRollbackWhenIntentPresent() {
        MigrationExecutionSummary rollbackPendingSummary = new MigrationExecutionSummary(
                executionId, sessionId, UUID.randomUUID(), userId,
                Platform.YOUTUBE, "PL_123", "https://youtube.com/playlist?list=PL_123",
                "Source Title", MigrationExecutionStatus.RUNNING, 2, 1, 0, 0, 0, 0,
                false, false, false, Instant.now(), null, Collections.<ExecutionTrackRecordDto>emptyList(),
                UUID.randomUUID(), Instant.now()
        );

        when(executionStateService.getExecution(executionId, userId)).thenReturn(rollbackPendingSummary);
        when(executionStateService.executeClaimedRollback(executionId, userId, 4L)).thenReturn(rollbackPendingSummary);

        MigrationExecutionSummary result = orchestrator.executeClaimedMigration(executionId, userId, 4L);

        assertThat(result).isNotNull();
        verify(executionStateService).executeClaimedRollback(executionId, userId, 4L);
        verify(executionStateService, never()).executeClaimedTrackInsertion(any(), any(), any(Long.class));
    }

    @Test
    @DisplayName("Orchestrator reconciles destination playlist when unallocated on claimed execution")
    void orchestratorReconcilesDestinationWhenUnallocated() {
        UUID opId = UUID.randomUUID();
        MigrationExecutionSummary unallocatedSummary = new MigrationExecutionSummary(
                executionId, sessionId, UUID.randomUUID(), userId,
                Platform.YOUTUBE, null, null,
                "Source Title", MigrationExecutionStatus.ALLOCATING_DESTINATION, 2, 0, 0, 0, 0, 0,
                false, false, false, Instant.now(), null, Collections.<ExecutionTrackRecordDto>emptyList(),
                opId, null
        );

        MigrationExecutionSummary confirmedSummary = new MigrationExecutionSummary(
                executionId, sessionId, UUID.randomUUID(), userId,
                Platform.YOUTUBE, "PL_reconciled", "https://youtube.com/playlist?list=PL_reconciled",
                "Source Title", MigrationExecutionStatus.RUNNING, 2, 0, 0, 0, 0, 0,
                false, false, false, Instant.now(), null, Collections.<ExecutionTrackRecordDto>emptyList(),
                opId, null
        );

        when(executionStateService.getExecution(executionId, userId)).thenReturn(unallocatedSummary);
        when(playlistReconciliationService.reconcilePlaylistCreation(eq(userId), eq(opId), eq("Source Title")))
                .thenReturn(PlaylistReconciliationResult.exactMatch(
                        new DestinationPlaylist("PL_reconciled", "Source Title", "Desc", "https://youtube.com/playlist?list=PL_reconciled", Platform.YOUTUBE),
                        "Found matching marker"
                ));
        when(executionStateService.confirmDestinationPlaylist(
                executionId, "PL_reconciled", "https://youtube.com/playlist?list=PL_reconciled",
                MigrationExecutionStatus.RUNNING, userId
        )).thenReturn(confirmedSummary);
        when(executionStateService.executeClaimedTrackInsertion(executionId, userId, 2L)).thenReturn(confirmedSummary);

        MigrationExecutionSummary result = orchestrator.executeClaimedMigration(executionId, userId, 2L);

        assertThat(result).isNotNull();
        verify(playlistReconciliationService).reconcilePlaylistCreation(userId, opId, "Source Title");
        verify(executionStateService).confirmDestinationPlaylist(
                executionId, "PL_reconciled", "https://youtube.com/playlist?list=PL_reconciled",
                MigrationExecutionStatus.RUNNING, userId
        );
        verify(executionStateService).executeClaimedTrackInsertion(executionId, userId, 2L);
    }

    @Test
    @DisplayName("Scheduler compensation passes preClaimStatus on rejection")
    void schedulerCompensationPassesPreClaimStatus() {
        stubTransactionManager();

        ClaimedExecution claim = new ClaimedExecution(
                executionId,
                userId,
                5L,
                Instant.now().plusSeconds(60),
                "test-worker-1",
                MigrationExecutionStatus.ALLOCATING_DESTINATION
        );

        when(executionRepository.claimNextEligibleExecution(any(), any(Long.class)))
                .thenReturn(Optional.of(claim));
        doThrow(new TaskRejectedException("Worker queue full")).when(taskExecutor).execute(any());

        when(executionRepository.compensateClaimRejection(
                eq(executionId), eq("test-worker-1"), eq(5L), any(Instant.class),
                eq(MigrationExecutionStatus.ALLOCATING_DESTINATION)
        )).thenReturn(1);

        scheduler.pollAndDispatch();

        verify(executionRepository).compensateClaimRejection(
                eq(executionId), eq("test-worker-1"), eq(5L), any(Instant.class),
                eq(MigrationExecutionStatus.ALLOCATING_DESTINATION)
        );
    }
}
