package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.migration.api.dto.InitializeExecutionRequest;
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
                Duration.ofMinutes(3), ReviewDecisionSource.CONFIRMED_EXACT, 0
        );
        ApprovedTrack track1 = new ApprovedTrack(
                1, "sp1", "Title 1", "Artist", "yt1", "Title 1 (Audio)", "Channel",
                Duration.ofMinutes(4), ReviewDecisionSource.USER_ACCEPTED, 1
        );

        validContext = new ApprovedMigrationContext(
                sessionId, UUID.randomUUID(), "sp_pl_1", "Source Playlist",
                "Target Playlist", "Description", true, 2, 2, 0, Instant.now(), List.of(track0, track1)
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
            DestinationPlaylistCreationResult playlistResult = DestinationPlaylistCreationResult.success(
                    "yt_pl_target", "https://youtube.com/playlist?list=yt_pl_target", "Target Playlist", 2, true
            );
            when(playlistCreationService.createDestinationPlaylist(request, userId))
                    .thenReturn(playlistResult);

            // Step 4: Durable execution initialized
            MigrationExecutionSummary initSummary = new MigrationExecutionSummary(
                    executionId, UUID.randomUUID(), sessionId, userId, "yt_pl_target",
                    MigrationExecutionStatus.INITIALIZED, 2, 0, 0, 0, 0, 0, 0,
                    Collections.emptyList(), false, false, Instant.now(), null
            );
            when(executionStateService.initializeExecution(any(InitializeExecutionRequest.class), eq(userId)))
                    .thenReturn(initSummary);

            // Step 5: Ordered track insertion completes
            MigrationExecutionSummary finalSummary = new MigrationExecutionSummary(
                    executionId, UUID.randomUUID(), sessionId, userId, "yt_pl_target",
                    MigrationExecutionStatus.COMPLETED, 2, 2, 0, 0, 0, 0, 0,
                    Collections.emptyList(), true, false, Instant.now(), Instant.now()
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
                    executionId, UUID.randomUUID(), sessionId, userId, "yt_pl_target",
                    MigrationExecutionStatus.COMPLETED, 2, 2, 0, 0, 0, 0, 0,
                    Collections.emptyList(), true, false, Instant.now(), Instant.now()
            );
            when(executionStateService.getExecutionByReviewSession(sessionId, userId))
                    .thenReturn(completedSummary);

            MigrationExecutionSummary result = orchestrator.executeApprovedMigration(request, userId);

            assertThat(result.status()).isEqualTo(MigrationExecutionStatus.COMPLETED);
            verify(playlistCreationService, never()).createDestinationPlaylist(any(), any());
            verify(executionStateService, never()).initializeExecution(any(), any());
            verify(executionStateService, never()).executeTrackInsertion(any(), any());
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

            verify(playlistCreationService, never()).createDestinationPlaylist(any(), any());
            verify(executionStateService, never()).initializeExecution(any(), any());
            verify(executionStateService, never()).executeTrackInsertion(any(), any());
        }

        @Test
        @DisplayName("Delegates recovery for eligible failed tracks via recoverMigration")
        void orchestratesRecovery() {
            RecoverExecutionRequest recoverReq = RecoverExecutionRequest.allFailedTracks(userId);
            MigrationExecutionSummary recoveredSummary = new MigrationExecutionSummary(
                    executionId, UUID.randomUUID(), sessionId, userId, "yt_pl_target",
                    MigrationExecutionStatus.COMPLETED, 2, 2, 0, 0, 0, 0, 0,
                    Collections.emptyList(), true, false, Instant.now(), Instant.now()
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
                    executionId, UUID.randomUUID(), sessionId, userId, "yt_pl_target",
                    MigrationExecutionStatus.RECOVERY_REQUIRED, 2, 1, 0, 1, 0, 0, 0,
                    Collections.emptyList(), false, false, Instant.now(), null
            );
            when(executionStateService.getExecutionByReviewSession(sessionId, userId))
                    .thenReturn(uncertainSummary);

            assertThatThrownBy(() -> orchestrator.executeApprovedMigration(request, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("requires reconciliation or recovery");

            verify(executionStateService, never()).executeTrackInsertion(any(), any());
        }

        @Test
        @DisplayName("Delegates remote state verification via reconcileMigration")
        void orchestratesReconciliation() {
            ReconcileExecutionRequest reconcileReq = ReconcileExecutionRequest.allTracks(userId, null);
            ExecutionReconciliationSummary reconcileSummary = new ExecutionReconciliationSummary(
                    executionId, userId, "yt_pl_target", MigrationExecutionStatus.COMPLETED,
                    1, 1, 0, 0, 0, Collections.emptyList(), Instant.now()
            );

            when(executionStateService.reconcileExecution(executionId, reconcileReq, userId))
                    .thenReturn(reconcileSummary);

            ExecutionReconciliationSummary result = orchestrator.reconcileMigration(executionId, reconcileReq, userId);

            assertThat(result.resultingExecutionStatus()).isEqualTo(MigrationExecutionStatus.COMPLETED);
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
            RollbackExecutionRequest rollbackReq = RollbackExecutionRequest.allAddedTracks(userId);
            MigrationExecutionSummary updatedExec = new MigrationExecutionSummary(
                    executionId, UUID.randomUUID(), sessionId, userId, "yt_pl_target",
                    MigrationExecutionStatus.ROLLED_BACK, 2, 0, 0, 0, 0, 2, 0,
                    Collections.emptyList(), false, false, Instant.now(), Instant.now()
            );

            ExecutionRollbackSummary rollbackSummary = new ExecutionRollbackSummary(
                    executionId, RollbackExecutionStatus.SUCCESS, 2, 2, 0, 0, Collections.emptyList(), updatedExec
            );

            when(executionStateService.rollbackExecution(executionId, rollbackReq, userId))
                    .thenReturn(rollbackSummary);

            ExecutionRollbackSummary result = orchestrator.rollbackMigration(executionId, rollbackReq, userId);

            assertThat(result.overallStatus()).isEqualTo(RollbackExecutionStatus.SUCCESS);
            assertThat(result.successfulRollbacks()).isEqualTo(2);
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
                    executionId, UUID.randomUUID(), sessionId, userId, "yt_pl_target",
                    MigrationExecutionStatus.RUNNING, 2, 0, 0, 0, 0, 0, 0,
                    Collections.emptyList(), false, false, Instant.now(), null
            );
            when(executionStateService.getExecutionByReviewSession(sessionId, userId))
                    .thenReturn(runningSummary);

            assertThatThrownBy(() -> orchestrator.executeApprovedMigration(request, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("currently in progress");

            verify(executionStateService, never()).executeTrackInsertion(any(), any());
        }
    }
}
