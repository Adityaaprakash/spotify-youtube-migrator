package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.migration.application.execution.contract.InitializeExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.DestinationPlaylistCreationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionReconciliationSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionRollbackSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionValidationException;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ReconcileExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RecoverExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackExecutionStatus;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ValidationFailureCode;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 11H: End-to-End Approved Migration Orchestrator Tests (Scenarios A-G)")
class ApprovedMigrationOrchestratorTest {

    @Mock
    private ApprovedMigrationExecutionService executionService;

    @Mock
    private DestinationPlaylistCreationService playlistCreationService;

    @Mock
    private MigrationExecutionStateService executionStateService;

    private DefaultApprovedMigrationOrchestrator orchestrator;

    private UUID userId;
    private UUID sessionId;
    private UUID executionId;
    private ApprovedMigrationExecutionRequest request;
    private ApprovedMigrationContext validContext;

    @BeforeEach
    void setUp() {
        orchestrator = new DefaultApprovedMigrationOrchestrator(
                executionService,
                playlistCreationService,
                executionStateService
        );

        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        executionId = UUID.randomUUID();
        request = ApprovedMigrationExecutionRequest.of(sessionId, userId);

        ApprovedTrack track0 = new ApprovedTrack(
                0, "sp0", "Title 0", "Artist", "yt0", "Title 0 (Audio)", "Channel",
                Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 0
        );
        ApprovedTrack track1 = new ApprovedTrack(
                1, "sp1", "Title 1", "Artist", "yt1", "Title 1 (Audio)", "Channel",
                Duration.ofMinutes(4), ReviewDecisionSource.USER_SELECTED, 1
        );

        validContext = new ApprovedMigrationContext(
                sessionId, UUID.randomUUID(), userId,
                "sp_pl_1", "Source Playlist",
                "Target Playlist", "Description", true,
                List.of(track0, track1),
                2, 2, 0,
                Instant.now()
        );
    }

    @Nested
    @DisplayName("Scenario A: Successful End-to-End Migration")
    class ScenarioASuccessfulMigration {

        @Test
        @DisplayName("Coordinates validation, playlist creation, initialization, and insertion to COMPLETED")
        void executesSuccessfulMigration() {
            // Step 1: Validation succeeds
            when(executionService.validateExecution(request, userId))
                    .thenReturn(ExecutionValidationResult.valid(validContext));

            // Step 2: No existing execution found
            when(executionStateService.getExecutionByReviewSession(sessionId, userId))
                    .thenThrow(new ResourceNotFoundException("Not found"));

            // Step 3: Playlist created
            DestinationPlaylistCreationResult playlistResult = new DestinationPlaylistCreationResult(
                    sessionId, validContext.planId(), userId,
                    Platform.YOUTUBE, "yt_pl_target", "https://youtube.com/playlist?list=yt_pl_target",
                    "Target Playlist", "Description", true, 2, Instant.now()
            );
            when(playlistCreationService.createDestinationPlaylist(request, userId))
                    .thenReturn(playlistResult);

            // Step 4: Durable execution initialized
            MigrationExecutionSummary initSummary = new MigrationExecutionSummary(
                    executionId, sessionId, validContext.planId(), userId,
                    Platform.YOUTUBE, "yt_pl_target", "https://youtube.com/playlist?list=yt_pl_target", "Source Playlist",
                    MigrationExecutionStatus.INITIALIZED, 2, 0, 0, 0, 0,
                    false, false, Instant.now(), null, Collections.emptyList()
            );
            when(executionStateService.initializeExecution(any(InitializeExecutionRequest.class), eq(userId)))
                    .thenReturn(initSummary);

            // Step 5: Ordered track insertion completes
            MigrationExecutionSummary finalSummary = new MigrationExecutionSummary(
                    executionId, sessionId, validContext.planId(), userId,
                    Platform.YOUTUBE, "yt_pl_target", "https://youtube.com/playlist?list=yt_pl_target", "Source Playlist",
                    MigrationExecutionStatus.COMPLETED, 2, 2, 0, 0, 0,
                    true, false, Instant.now(), Instant.now(), Collections.emptyList()
            );
            when(executionStateService.executeTrackInsertion(executionId, userId))
                    .thenReturn(finalSummary);

            // Execute
            MigrationExecutionSummary result = orchestrator.executeApprovedMigration(request, userId);

            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo(MigrationExecutionStatus.COMPLETED);
            assertThat(result.successfulInsertions()).isEqualTo(2);
            assertThat(result.failedInsertions()).isEqualTo(0);

            verify(executionService).validateExecution(request, userId);
            verify(playlistCreationService).createDestinationPlaylist(request, userId);
            verify(executionStateService).initializeExecution(any(InitializeExecutionRequest.class), eq(userId));
            verify(executionStateService).executeTrackInsertion(executionId, userId);
        }

        @Test
        @DisplayName("Idempotent: Already COMPLETED execution returns summary without re-creating playlist or inserting")
        void idempotentCompletedExecution() {
            when(executionService.validateExecution(request, userId))
                    .thenReturn(ExecutionValidationResult.valid(validContext));

            MigrationExecutionSummary completedSummary = new MigrationExecutionSummary(
                    executionId, sessionId, validContext.planId(), userId,
                    Platform.YOUTUBE, "yt_pl_target", "https://youtube.com/playlist?list=yt_pl_target", "Source Playlist",
                    MigrationExecutionStatus.COMPLETED, 2, 2, 0, 0, 0,
                    true, false, Instant.now(), Instant.now(), Collections.emptyList()
            );
            when(executionStateService.getExecutionByReviewSession(sessionId, userId))
                    .thenReturn(completedSummary);

            MigrationExecutionSummary result = orchestrator.executeApprovedMigration(request, userId);

            assertThat(result.status()).isEqualTo(MigrationExecutionStatus.COMPLETED);
            verify(playlistCreationService, never()).createDestinationPlaylist(any(ApprovedMigrationExecutionRequest.class), any());
            verify(executionStateService, never()).initializeExecution(any(InitializeExecutionRequest.class), any());
            verify(executionStateService, never()).executeTrackInsertion(any(UUID.class), any());
        }
    }

    @Nested
    @DisplayName("Scenario B: Validation & Failure Recovery Orchestration")
    class ScenarioBFailureAndRecovery {

        @Test
        @DisplayName("Validation failure halts orchestration before any destination mutations")
        void validationFailureHaltsWorkflow() {
            when(executionService.validateExecution(request, userId))
                    .thenReturn(ExecutionValidationResult.invalid(
                            ValidationFailureCode.INCOMPLETE_REVIEW,
                            "Review session has pending decisions"
                    ));

            assertThatThrownBy(() -> orchestrator.executeApprovedMigration(request, userId))
                    .isInstanceOf(MigrationExecutionValidationException.class)
                    .hasMessageContaining("validation failed");

            verify(playlistCreationService, never()).createDestinationPlaylist(any(ApprovedMigrationExecutionRequest.class), any());
            verify(executionStateService, never()).initializeExecution(any(InitializeExecutionRequest.class), any());
            verify(executionStateService, never()).executeTrackInsertion(any(UUID.class), any());
        }

        @Test
        @DisplayName("Delegates recovery for eligible failed tracks via recoverMigration")
        void orchestratesRecovery() {
            RecoverExecutionRequest recoverReq = new RecoverExecutionRequest(null, null, userId);
            MigrationExecutionSummary recoveredSummary = new MigrationExecutionSummary(
                    executionId, sessionId, validContext.planId(), userId,
                    Platform.YOUTUBE, "yt_pl_target", "https://youtube.com/playlist?list=yt_pl_target", "Source Playlist",
                    MigrationExecutionStatus.COMPLETED, 2, 2, 0, 0, 0,
                    true, false, Instant.now(), Instant.now(), Collections.emptyList()
            );

            when(executionStateService.recoverExecution(executionId, recoverReq, userId))
                    .thenReturn(recoveredSummary);

            MigrationExecutionSummary result = orchestrator.recoverMigration(executionId, recoverReq, userId);

            assertThat(result.status()).isEqualTo(MigrationExecutionStatus.COMPLETED);
            assertThat(result.successfulInsertions()).isEqualTo(2);
            verify(executionStateService).recoverExecution(executionId, recoverReq, userId);
        }
    }

    @Nested
    @DisplayName("Scenario C & E: Ambiguous Mutation & Reconciliation Orchestration")
    class ScenarioAmbiguityAndReconciliation {

        @Test
        @DisplayName("Uncertain execution in RECOVERY_REQUIRED blocks execution until reconciled")
        void uncertainExecutionBlocksOrchestration() {
            when(executionService.validateExecution(request, userId))
                    .thenReturn(ExecutionValidationResult.valid(validContext));

            MigrationExecutionSummary uncertainSummary = new MigrationExecutionSummary(
                    executionId, sessionId, validContext.planId(), userId,
                    Platform.YOUTUBE, "yt_pl_target", "https://youtube.com/playlist?list=yt_pl_target", "Source Playlist",
                    MigrationExecutionStatus.RECOVERY_REQUIRED, 2, 1, 0, 1, 0,
                    false, false, Instant.now(), null, Collections.emptyList()
            );
            when(executionStateService.getExecutionByReviewSession(sessionId, userId))
                    .thenReturn(uncertainSummary);

            assertThatThrownBy(() -> orchestrator.executeApprovedMigration(request, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("requires reconciliation or recovery");

            verify(executionStateService, never()).executeTrackInsertion(any(UUID.class), any());
        }

        @Test
        @DisplayName("Delegates remote state verification via reconcileMigration")
        void orchestratesReconciliation() {
            ReconcileExecutionRequest reconcileReq = ReconcileExecutionRequest.allTracks(userId, null);
            ExecutionReconciliationSummary reconcileSummary = new ExecutionReconciliationSummary(
                    executionId,
                    MigrationExecutionStatus.RECOVERY_REQUIRED,
                    MigrationExecutionStatus.COMPLETED,
                    1, 1, 1, 0, 0, 1, 0, 0,
                    Collections.emptyList(),
                    true
            );

            when(executionStateService.reconcileExecution(executionId, reconcileReq, userId))
                    .thenReturn(reconcileSummary);

            ExecutionReconciliationSummary result = orchestrator.reconcileMigration(executionId, reconcileReq, userId);

            assertThat(result.resolvedExecutionStatus()).isEqualTo(MigrationExecutionStatus.COMPLETED);
            assertThat(result.confirmedInsertedCount()).isEqualTo(1);
            verify(executionStateService).reconcileExecution(executionId, reconcileReq, userId);
        }
    }

    @Nested
    @DisplayName("Scenario D: Partial Rollback Orchestration")
    class ScenarioDPartialRollback {

        @Test
        @DisplayName("Delegates partial rollback via rollbackMigration")
        void orchestratesRollback() {
            RollbackExecutionRequest rollbackReq = new RollbackExecutionRequest(Collections.emptyList(), userId);
            MigrationExecutionSummary updatedExec = new MigrationExecutionSummary(
                    executionId, sessionId, validContext.planId(), userId,
                    Platform.YOUTUBE, "yt_pl_target", "https://youtube.com/playlist?list=yt_pl_target", "Source Playlist",
                    MigrationExecutionStatus.ROLLED_BACK, 2, 0, 0, 0, 0, 2,
                    false, false, false, Instant.now(), Instant.now(), Collections.emptyList()
            );

            ExecutionRollbackSummary rollbackSummary = new ExecutionRollbackSummary(
                    executionId, RollbackExecutionStatus.SUCCESS, 2, 2, 0, 0, Collections.emptyList(), updatedExec
            );

            when(executionStateService.rollbackExecution(executionId, rollbackReq, userId))
                    .thenReturn(rollbackSummary);

            ExecutionRollbackSummary result = orchestrator.rollbackMigration(executionId, rollbackReq, userId);

            assertThat(result.overallStatus()).isEqualTo(RollbackExecutionStatus.SUCCESS);
            assertThat(result.successfullyRolledBack()).isEqualTo(2);
            assertThat(result.updatedExecution().status()).isEqualTo(MigrationExecutionStatus.ROLLED_BACK);
            verify(executionStateService).rollbackExecution(executionId, rollbackReq, userId);
        }
    }

    @Nested
    @DisplayName("Scenario F: Multi-User Security and Anti-IDOR Isolation")
    class ScenarioFSecurityIsolation {

        @Test
        @DisplayName("Anti-impersonation: Rejects request when caller ID is null")
        void rejectsNullUserId() {
            assertThatThrownBy(() -> orchestrator.executeApprovedMigration(request, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("user ID must not be null");
        }

        @Test
        @DisplayName("Anti-IDOR: Rejects execution retrieval when user does not own the execution")
        void antiIdorOnExecutionRetrieval() {
            when(executionStateService.getExecution(executionId, userId))
                    .thenThrow(new ResourceNotFoundException("Migration execution not found: " + executionId));

            assertThatThrownBy(() -> orchestrator.getExecutionSummary(executionId, userId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("not found");
        }
    }

    @Nested
    @DisplayName("Scenario G: Concurrency and Conflict Protection")
    class ScenarioGConcurrencyGuards {

        @Test
        @DisplayName("Rejects execution if another worker currently has status RUNNING")
        void rejectsIfRunningConcurrently() {
            when(executionService.validateExecution(request, userId))
                    .thenReturn(ExecutionValidationResult.valid(validContext));

            MigrationExecutionSummary runningSummary = new MigrationExecutionSummary(
                    executionId, sessionId, validContext.planId(), userId,
                    Platform.YOUTUBE, "yt_pl_target", "https://youtube.com/playlist?list=yt_pl_target", "Source Playlist",
                    MigrationExecutionStatus.RUNNING, 2, 0, 0, 0, 0,
                    false, false, Instant.now(), null, Collections.emptyList()
            );
            when(executionStateService.getExecutionByReviewSession(sessionId, userId))
                    .thenReturn(runningSummary);

            assertThatThrownBy(() -> orchestrator.executeApprovedMigration(request, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("currently in progress");

            verify(executionStateService, never()).executeTrackInsertion(any(UUID.class), any());
        }
    }

    @Nested
    @DisplayName("Phase 12C: Queued Migration Preparation & Worker Execution Tests")
    class Phase12CQueuedMigrationTests {

        @Test
        @DisplayName("prepareQueuedMigration validates, creates playlist, and persists QUEUED execution")
        void prepareQueuedMigrationSuccess() {
            when(executionService.validateExecution(request, userId))
                    .thenReturn(ExecutionValidationResult.valid(validContext));
            when(executionStateService.getExecutionByReviewSession(sessionId, userId))
                    .thenThrow(new ResourceNotFoundException("No existing execution"));

            DestinationPlaylistCreationResult playlistResult = new DestinationPlaylistCreationResult(
                    "yt_pl_target", "https://youtube.com/playlist?list=yt_pl_target", "Target Playlist", Instant.now()
            );
            when(playlistCreationService.createDestinationPlaylist(request, userId)).thenReturn(playlistResult);

            MigrationExecutionSummary queuedSummary = new MigrationExecutionSummary(
                    executionId, sessionId, validContext.planId(), userId,
                    Platform.YOUTUBE, "yt_pl_target", "https://youtube.com/playlist?list=yt_pl_target", "Source Playlist",
                    MigrationExecutionStatus.QUEUED, 2, 0, 0, 0, 0,
                    false, false, null, null, Collections.emptyList()
            );
            when(executionStateService.initializeQueuedExecution(any(InitializeExecutionRequest.class), eq(userId)))
                    .thenReturn(queuedSummary);

            MigrationExecutionSummary result = orchestrator.prepareQueuedMigration(request, userId);

            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo(MigrationExecutionStatus.QUEUED);
            assertThat(result.executionId()).isEqualTo(executionId);

            verify(executionService).validateExecution(request, userId);
            verify(playlistCreationService).createDestinationPlaylist(request, userId);
            verify(executionStateService).initializeQueuedExecution(any(InitializeExecutionRequest.class), eq(userId));
        }

        @Test
        @DisplayName("prepareQueuedMigration returns existing summary when already COMPLETED")
        void prepareQueuedMigrationReturnsWhenCompleted() {
            when(executionService.validateExecution(request, userId))
                    .thenReturn(ExecutionValidationResult.valid(validContext));

            MigrationExecutionSummary completedSummary = new MigrationExecutionSummary(
                    executionId, sessionId, validContext.planId(), userId,
                    Platform.YOUTUBE, "yt_pl_target", "https://youtube.com/playlist?list=yt_pl_target", "Source Playlist",
                    MigrationExecutionStatus.COMPLETED, 2, 2, 0, 0, 0,
                    false, false, Instant.now(), Instant.now(), Collections.emptyList()
            );
            when(executionStateService.getExecutionByReviewSession(sessionId, userId)).thenReturn(completedSummary);

            MigrationExecutionSummary result = orchestrator.prepareQueuedMigration(request, userId);

            assertThat(result.status()).isEqualTo(MigrationExecutionStatus.COMPLETED);
            verify(playlistCreationService, never()).createDestinationPlaylist(any(), any());
            verify(executionStateService, never()).initializeQueuedExecution(any(), any());
        }

        @Test
        @DisplayName("prepareQueuedMigration rejects when execution is already QUEUED")
        void prepareQueuedMigrationRejectsWhenAlreadyQueued() {
            when(executionService.validateExecution(request, userId))
                    .thenReturn(ExecutionValidationResult.valid(validContext));

            MigrationExecutionSummary queuedSummary = new MigrationExecutionSummary(
                    executionId, sessionId, validContext.planId(), userId,
                    Platform.YOUTUBE, "yt_pl_target", "https://youtube.com/playlist?list=yt_pl_target", "Source Playlist",
                    MigrationExecutionStatus.QUEUED, 2, 0, 0, 0, 0,
                    false, false, null, null, Collections.emptyList()
            );
            when(executionStateService.getExecutionByReviewSession(sessionId, userId)).thenReturn(queuedSummary);

            assertThatThrownBy(() -> orchestrator.prepareQueuedMigration(request, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("already queued");
        }

        @Test
        @DisplayName("prepareQueuedMigration rejects when execution is currently RUNNING")
        void prepareQueuedMigrationRejectsWhenRunning() {
            when(executionService.validateExecution(request, userId))
                    .thenReturn(ExecutionValidationResult.valid(validContext));

            MigrationExecutionSummary runningSummary = new MigrationExecutionSummary(
                    executionId, sessionId, validContext.planId(), userId,
                    Platform.YOUTUBE, "yt_pl_target", "https://youtube.com/playlist?list=yt_pl_target", "Source Playlist",
                    MigrationExecutionStatus.RUNNING, 2, 0, 0, 0, 0,
                    false, false, Instant.now(), null, Collections.emptyList()
            );
            when(executionStateService.getExecutionByReviewSession(sessionId, userId)).thenReturn(runningSummary);

            assertThatThrownBy(() -> orchestrator.prepareQueuedMigration(request, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("currently in progress");
        }

        @Test
        @DisplayName("prepareQueuedMigration rejects when validation fails")
        void prepareQueuedMigrationRejectsWhenValidationFails() {
            when(executionService.validateExecution(request, userId))
                    .thenReturn(ExecutionValidationResult.invalid(List.of(
                            new com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationError(
                                    ValidationFailureCode.INCOMPLETE_REVIEW, "Review not complete", null
                            )
                    )));

            assertThatThrownBy(() -> orchestrator.prepareQueuedMigration(request, userId))
                    .isInstanceOf(MigrationExecutionValidationException.class)
                    .hasMessageContaining("validation failed");

            verify(playlistCreationService, never()).createDestinationPlaylist(any(), any());
        }

        @Test
        @DisplayName("executeQueuedMigration delegates directly to executionStateService.executeQueuedTrackInsertion")
        void executeQueuedMigrationSuccess() {
            MigrationExecutionSummary completedSummary = new MigrationExecutionSummary(
                    executionId, sessionId, validContext.planId(), userId,
                    Platform.YOUTUBE, "yt_pl_target", "https://youtube.com/playlist?list=yt_pl_target", "Source Playlist",
                    MigrationExecutionStatus.COMPLETED, 2, 2, 0, 0, 0,
                    false, false, Instant.now(), Instant.now(), Collections.emptyList()
            );
            when(executionStateService.executeQueuedTrackInsertion(executionId, userId)).thenReturn(completedSummary);

            MigrationExecutionSummary result = orchestrator.executeQueuedMigration(executionId, userId);

            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo(MigrationExecutionStatus.COMPLETED);
            verify(executionStateService).executeQueuedTrackInsertion(executionId, userId);
        }

        @Test
        @DisplayName("executeQueuedMigration rejects null arguments with IllegalArgumentException")
        void executeQueuedMigrationRejectsNullArguments() {
            assertThatThrownBy(() -> orchestrator.executeQueuedMigration(null, userId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Execution ID must not be null");

            assertThatThrownBy(() -> orchestrator.executeQueuedMigration(executionId, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Authenticated user ID must not be null");
        }
    }
}
