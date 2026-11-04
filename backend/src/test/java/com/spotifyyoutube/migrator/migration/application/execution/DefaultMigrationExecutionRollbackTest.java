package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionRollbackSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackRecordDto;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackTrackOutcomeDto;
import com.spotifyyoutube.migrator.migration.application.execution.failure.ExecutionFailureClassifier;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionRollbackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.RollbackRecordStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionRollbackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Phase 11F: DefaultMigrationExecutionRollbackTest")
class DefaultMigrationExecutionRollbackTest {

    private MigrationExecutionRepository executionRepository;
    private ExecutionTrackRecordRepository trackRecordRepository;
    private ExecutionRollbackRecordRepository rollbackRecordRepository;
    private ReviewSessionRepository reviewSessionRepository;
    private ApprovedMigrationExecutionService executionService;
    private MusicDestination musicDestination;
    private TransactionTemplate transactionTemplate;
    private ExecutionFailureClassifier failureClassifier;

    private DefaultMigrationExecutionStateService service;

    private UUID userId;
    private User testUser;
    private UUID sessionId;
    private ReviewSession reviewSession;
    private ApprovedMigrationContext approvedContext;
    private UUID executionId;
    private MigrationExecution execution;

    @BeforeEach
    void setUp() {
        executionRepository = mock(MigrationExecutionRepository.class);
        trackRecordRepository = mock(ExecutionTrackRecordRepository.class);
        rollbackRecordRepository = mock(ExecutionRollbackRecordRepository.class);
        reviewSessionRepository = mock(ReviewSessionRepository.class);
        executionService = mock(ApprovedMigrationExecutionService.class);
        musicDestination = mock(MusicDestination.class);
        transactionTemplate = mock(TransactionTemplate.class);
        failureClassifier = new ExecutionFailureClassifier();

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        service = new DefaultMigrationExecutionStateService(
                executionRepository,
                trackRecordRepository,
                rollbackRecordRepository,
                reviewSessionRepository,
                executionService,
                musicDestination,
                failureClassifier,
                transactionTemplate
        );

        userId = UUID.randomUUID();
        testUser = new User("rollback-tester@example.com");
        testUser.setId(userId);

        sessionId = UUID.randomUUID();
        reviewSession = new ReviewSession(testUser, "sp_pl_rb", "Rollback Test Playlist", UUID.randomUUID());
        reviewSession.setId(sessionId);
        reviewSession.setStatus(ReviewSessionStatus.COMPLETED);

        ApprovedTrack track0 = new ApprovedTrack(
                0,
                "sp_1",
                "Song One",
                "Artist A",
                "yt_video_1",
                "Song One (Audio)",
                "Channel A",
                Duration.ofMinutes(3),
                ReviewDecisionSource.AUTOMATIC,
                0
        );

        ApprovedTrack track1 = new ApprovedTrack(
                1,
                "sp_2",
                "Song Two",
                "Artist B",
                "yt_video_2",
                "Song Two (Audio)",
                "Channel B",
                Duration.ofMinutes(4),
                ReviewDecisionSource.USER_SELECTED,
                1
        );

        approvedContext = new ApprovedMigrationContext(
                sessionId,
                UUID.randomUUID(),
                userId,
                "sp_pl_rb",
                "Rollback Test Playlist",
                "Target Playlist",
                "Description",
                false,
                List.of(track0, track1),
                2,
                2,
                0,
                Instant.now()
        );

        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(reviewSession));

        executionId = UUID.randomUUID();
        execution = new MigrationExecution(testUser, reviewSession, approvedContext, "yt_pl_target", null);
        execution.setId(executionId);
        for (ExecutionTrackRecord tr : execution.getTrackRecords()) {
            tr.setId(UUID.randomUUID());
        }

        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        when(executionRepository.save(any(MigrationExecution.class))).thenAnswer(i -> i.getArgument(0));
        when(executionRepository.saveAndFlush(any(MigrationExecution.class))).thenAnswer(i -> i.getArgument(0));
        when(executionRepository.claimExecutionRunningWithVersion(any(), any(), anyLong(), any())).thenReturn(1);
        when(trackRecordRepository.claimTrackRollingBack(any(), any())).thenAnswer(invocation -> {
            UUID trackId = invocation.getArgument(0);
            for (ExecutionTrackRecord tr : execution.getTrackRecords()) {
                if (tr.getId().equals(trackId) && tr.getStatus() == ExecutionTrackStatus.ADDED) {
                    tr.markRollingBack();
                    return 1;
                }
            }
            return 0;
        });
        when(trackRecordRepository.save(any(ExecutionTrackRecord.class))).thenAnswer(i -> i.getArgument(0));
        when(trackRecordRepository.saveAndFlush(any(ExecutionTrackRecord.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Nested
    @DisplayName("Partial Rollback Operations")
    class RollbackOperations {

        @Test
        @DisplayName("Successfully rolls back a single confirmed inserted playlist item")
        void rollsBackSingleItemSuccessfully() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markAdded("yt_item_0");

            ExecutionTrackRecord track1 = execution.getTrackRecords().get(1);
            track1.markAdding();
            track1.markAdded("yt_item_1");

            execution.reconcileCounts();
            execution.setStatus(MigrationExecutionStatus.COMPLETED);

            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId))
                    .thenReturn(List.of(track0, track1));
            when(trackRecordRepository.findById(track0.getId())).thenReturn(Optional.of(track0));
            when(trackRecordRepository.findById(track1.getId())).thenReturn(Optional.of(track1));
            when(rollbackRecordRepository.save(any(ExecutionRollbackRecord.class))).thenAnswer(i -> i.getArgument(0));

            doNothing().when(musicDestination).removeTrackFromPlaylist(userId, "yt_item_0");

            RollbackExecutionRequest request = RollbackExecutionRequest.forPositions(List.of(0));
            ExecutionRollbackSummary summary = service.rollbackExecution(executionId, request, userId);

            // Verify remote call targeting playlist item ID
            verify(musicDestination, times(1)).removeTrackFromPlaylist(userId, "yt_item_0");
            verify(musicDestination, never()).removeTrackFromPlaylist(userId, "yt_item_1");

            // Verify state mutations
            assertThat(track0.getStatus()).isEqualTo(ExecutionTrackStatus.ROLLED_BACK);
            assertThat(track1.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);

            assertThat(summary.overallStatus()).isEqualTo(RollbackExecutionStatus.SUCCESS);
            assertThat(summary.totalRequested()).isEqualTo(1);
            assertThat(summary.successfullyRolledBack()).isEqualTo(1);
            assertThat(summary.failedRollbacks()).isEqualTo(0);
            assertThat(summary.trackOutcomes()).hasSize(1);
            assertThat(summary.trackOutcomes().get(0).status()).isEqualTo(RollbackRecordStatus.ROLLED_BACK);

            // Verify aggregate execution status is PARTIALLY_ROLLED_BACK because track 1 is still ADDED
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.PARTIALLY_ROLLED_BACK);
            assertThat(execution.getRolledBackInsertions()).isEqualTo(1);
        }

        @Test
        @DisplayName("Successfully rolls back all confirmed inserted items when target list is omitted")
        void rollsBackAllConfirmedItemsWhenTargetOmitted() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markAdded("yt_item_0");

            ExecutionTrackRecord track1 = execution.getTrackRecords().get(1);
            track1.markAdding();
            track1.markAdded("yt_item_1");

            execution.reconcileCounts();
            execution.setStatus(MigrationExecutionStatus.COMPLETED);

            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId))
                    .thenReturn(List.of(track0, track1));
            when(trackRecordRepository.findById(track0.getId())).thenReturn(Optional.of(track0));
            when(trackRecordRepository.findById(track1.getId())).thenReturn(Optional.of(track1));
            when(rollbackRecordRepository.save(any(ExecutionRollbackRecord.class))).thenAnswer(i -> i.getArgument(0));

            RollbackExecutionRequest request = RollbackExecutionRequest.all();
            ExecutionRollbackSummary summary = service.rollbackExecution(executionId, request, userId);

            verify(musicDestination, times(1)).removeTrackFromPlaylist(userId, "yt_item_0");
            verify(musicDestination, times(1)).removeTrackFromPlaylist(userId, "yt_item_1");

            assertThat(track0.getStatus()).isEqualTo(ExecutionTrackStatus.ROLLED_BACK);
            assertThat(track1.getStatus()).isEqualTo(ExecutionTrackStatus.ROLLED_BACK);

            assertThat(summary.overallStatus()).isEqualTo(RollbackExecutionStatus.SUCCESS);
            assertThat(summary.successfullyRolledBack()).isEqualTo(2);

            // Both items rolled back -> aggregate status is ROLLED_BACK
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.ROLLED_BACK);
            assertThat(execution.getRolledBackInsertions()).isEqualTo(2);
        }

        @Test
        @DisplayName("Handles remote 404 Not Found as confirmed deletion (already deleted remotely)")
        void handles404AsConfirmedDeletion() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markAdded("yt_item_0");

            execution.reconcileCounts();
            execution.setStatus(MigrationExecutionStatus.PARTIALLY_COMPLETED);

            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId))
                    .thenReturn(List.of(track0));
            when(trackRecordRepository.findById(track0.getId())).thenReturn(Optional.of(track0));
            when(rollbackRecordRepository.save(any(ExecutionRollbackRecord.class))).thenAnswer(i -> i.getArgument(0));

            // Remote returns 404
            doThrow(new ResourceNotFoundException("Not Found"))
                    .when(musicDestination).removeTrackFromPlaylist(userId, "yt_item_0");

            RollbackExecutionRequest request = RollbackExecutionRequest.forPositions(List.of(0));
            ExecutionRollbackSummary summary = service.rollbackExecution(executionId, request, userId);

            assertThat(summary.overallStatus()).isEqualTo(RollbackExecutionStatus.SUCCESS);
            assertThat(summary.successfullyRolledBack()).isEqualTo(1);
            assertThat(track0.getStatus()).isEqualTo(ExecutionTrackStatus.ROLLED_BACK);
        }

        @Test
        @DisplayName("State-Level Idempotency: Repeating rollback request on already ROLLED_BACK item skips remote call")
        void idempotentRollbackSkipsRemoteCall() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markAdded("yt_item_0");
            track0.markRolledBack("Previously rolled back");

            execution.reconcileCounts();
            execution.setStatus(MigrationExecutionStatus.ROLLED_BACK);

            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId))
                    .thenReturn(List.of(track0));
            when(trackRecordRepository.findById(track0.getId())).thenReturn(Optional.of(track0));
            when(rollbackRecordRepository.save(any(ExecutionRollbackRecord.class))).thenAnswer(i -> i.getArgument(0));

            RollbackExecutionRequest request = RollbackExecutionRequest.forPositions(List.of(0));
            ExecutionRollbackSummary summary = service.rollbackExecution(executionId, request, userId);

            // Remote call MUST NOT be made
            verify(musicDestination, never()).removeTrackFromPlaylist(any(), any());

            assertThat(summary.overallStatus()).isEqualTo(RollbackExecutionStatus.NO_OP);
            assertThat(summary.trackOutcomes()).hasSize(1);
            RollbackTrackOutcomeDto outcome = summary.trackOutcomes().get(0);
            assertThat(outcome.status()).isEqualTo(RollbackRecordStatus.ALREADY_ROLLED_BACK);
        }

        @Test
        @DisplayName("Handles confirmed remote rejection during rollback by reverting ROLLING_BACK to ADDED")
        void handlesRemoteRejectionDuringRollback() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markAdded("yt_item_0");

            ExecutionTrackRecord track1 = execution.getTrackRecords().get(1);
            track1.markAdding();
            track1.markAdded("yt_item_1");

            execution.reconcileCounts();
            execution.setStatus(MigrationExecutionStatus.COMPLETED);

            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId))
                    .thenReturn(List.of(track0, track1));
            when(trackRecordRepository.findById(track0.getId())).thenReturn(Optional.of(track0));
            when(trackRecordRepository.findById(track1.getId())).thenReturn(Optional.of(track1));
            when(rollbackRecordRepository.save(any(ExecutionRollbackRecord.class))).thenAnswer(i -> i.getArgument(0));

            // Track 0 succeeds, Track 1 gets 403 Forbidden
            doNothing().when(musicDestination).removeTrackFromPlaylist(userId, "yt_item_0");
            doThrow(new ExternalProviderException("YouTube API 403 Forbidden", 403))
                    .when(musicDestination).removeTrackFromPlaylist(userId, "yt_item_1");

            RollbackExecutionRequest request = RollbackExecutionRequest.forPositions(List.of(0, 1));
            ExecutionRollbackSummary summary = service.rollbackExecution(executionId, request, userId);

            assertThat(summary.overallStatus()).isEqualTo(RollbackExecutionStatus.PARTIAL_SUCCESS);
            assertThat(summary.successfullyRolledBack()).isEqualTo(1);
            assertThat(summary.failedRollbacks()).isEqualTo(1);

            // Track 0 was rolled back, Track 1 reverted back to ADDED
            assertThat(track0.getStatus()).isEqualTo(ExecutionTrackStatus.ROLLED_BACK);
            assertThat(track1.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);

            // Audit records: 1 ROLLED_BACK, 1 FAILED
            verify(rollbackRecordRepository, times(2)).save(any(ExecutionRollbackRecord.class));
        }

        @Test
        @DisplayName("Handles network timeout during remote DELETE as UNCERTAIN, blocking blind retry")
        void handlesNetworkTimeoutDuringRollbackAsUncertain() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markAdded("yt_item_0");

            execution.reconcileCounts();
            execution.setStatus(MigrationExecutionStatus.COMPLETED);

            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId))
                    .thenReturn(List.of(track0));
            when(trackRecordRepository.findById(track0.getId())).thenReturn(Optional.of(track0));
            when(rollbackRecordRepository.save(any(ExecutionRollbackRecord.class))).thenAnswer(i -> i.getArgument(0));

            // Remote DELETE times out
            doThrow(new ResourceAccessException("Connection timed out"))
                    .when(musicDestination).removeTrackFromPlaylist(userId, "yt_item_0");

            RollbackExecutionRequest request = RollbackExecutionRequest.forPositions(List.of(0));
            ExecutionRollbackSummary summary = service.rollbackExecution(executionId, request, userId);

            assertThat(summary.overallStatus()).isEqualTo(RollbackExecutionStatus.FAILED);
            assertThat(summary.failedRollbacks()).isEqualTo(1);
            assertThat(track0.getStatus()).isEqualTo(ExecutionTrackStatus.UNCERTAIN);

            // Execution status transitions to RECOVERY_REQUIRED because outcome is uncertain
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.RECOVERY_REQUIRED);
        }
    }

    @Nested
    @DisplayName("Rollback Safety & Validation Guardrails")
    class SafetyAndValidation {

        @Test
        @DisplayName("Rejects rollback if target position does not exist in execution")
        void rejectsRollbackOfNonExistentPosition() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markAdded("yt_item_0");

            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId))
                    .thenReturn(List.of(track0));

            RollbackExecutionRequest request = RollbackExecutionRequest.forPositions(List.of(999));

            assertThatThrownBy(() -> service.rollbackExecution(executionId, request, userId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not exist in execution");

            verify(musicDestination, never()).removeTrackFromPlaylist(any(), any());
        }

        @Test
        @DisplayName("Rejects rollback if target track was never inserted (status FAILED)")
        void rejectsRollbackOfUninsertedTrack() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markFailed("Failed before insertion");

            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId))
                    .thenReturn(List.of(track0));

            RollbackExecutionRequest request = RollbackExecutionRequest.forPositions(List.of(0));

            assertThatThrownBy(() -> service.rollbackExecution(executionId, request, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("never inserted");

            verify(musicDestination, never()).removeTrackFromPlaylist(any(), any());
        }

        @Test
        @DisplayName("Rejects rollback if execution is currently RUNNING")
        void rejectsRollbackWhenRunning() {
            execution.setStatus(MigrationExecutionStatus.RUNNING);

            RollbackExecutionRequest request = RollbackExecutionRequest.forPositions(List.of(0));

            assertThatThrownBy(() -> service.rollbackExecution(executionId, request, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("currently running");

            verify(musicDestination, never()).removeTrackFromPlaylist(any(), any());
        }

        @Test
        @DisplayName("Anti-IDOR: Rejects rollback if user ID does not match execution owner")
        void rejectsCrossUserRollback() {
            UUID intruderId = UUID.randomUUID();
            RollbackExecutionRequest request = RollbackExecutionRequest.forPositions(List.of(0));

            assertThatThrownBy(() -> service.rollbackExecution(executionId, request, intruderId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Migration execution not found");

            verify(musicDestination, never()).removeTrackFromPlaylist(any(), any());
        }

        @Test
        @DisplayName("Retrieves rollback audit history ordered by targetPosition ascending")
        void retrievesRollbackHistory() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markAdded("yt_item_0");

            ExecutionTrackRecord track1 = execution.getTrackRecords().get(1);
            track1.markAdding();
            track1.markAdded("yt_item_1");

            ExecutionRollbackRecord r0 = ExecutionRollbackRecord.success(execution, track0);
            ExecutionRollbackRecord r1 = ExecutionRollbackRecord.failed(execution, track1, "YouTube 500 error");

            when(rollbackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId))
                    .thenReturn(List.of(r0, r1));

            List<RollbackRecordDto> history = service.getRollbackHistory(executionId, userId);

            assertThat(history).hasSize(2);
            assertThat(history.get(0).destinationPlaylistItemId()).isEqualTo("yt_item_0");
            assertThat(history.get(0).status()).isEqualTo(RollbackRecordStatus.ROLLED_BACK);
            assertThat(history.get(1).destinationPlaylistItemId()).isEqualTo("yt_item_1");
            assertThat(history.get(1).status()).isEqualTo(RollbackRecordStatus.FAILED);
        }

        @Test
        @DisplayName("Concurrency: Two simultaneous rollback claims result in second claim failing fast with InvalidStateException (HTTP 409)")
        void twoSimultaneousExecutionClaimsForRollback_secondClaimFailsWithConflict() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markAdded("yt_item_0");

            execution.reconcileCounts();
            execution.setStatus(MigrationExecutionStatus.COMPLETED);

            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId))
                    .thenReturn(List.of(track0));

            // Another instance already claimed this execution for rollback, returning 0 rows updated
            when(executionRepository.claimExecutionRunningWithVersion(any(), any(), anyLong(), any()))
                    .thenReturn(0);

            RollbackExecutionRequest request = RollbackExecutionRequest.all();

            assertThatThrownBy(() -> service.rollbackExecution(executionId, request, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("could not be claimed for rollback");

            verify(musicDestination, never()).removeTrackFromPlaylist(any(), any());
        }

        @Test
        @DisplayName("Concurrency: Stale optimistic-lock version during rollback claim throws InvalidStateException")
        void staleOptimisticLockVersionDuringRollbackClaim() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markAdded("yt_item_0");

            execution.reconcileCounts();
            execution.setStatus(MigrationExecutionStatus.COMPLETED);

            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId))
                    .thenReturn(List.of(track0));

            // Stale version mismatch results in 0 rows updated
            when(executionRepository.claimExecutionRunningWithVersion(eq(executionId), any(), anyLong(), any()))
                    .thenReturn(0);

            RollbackExecutionRequest request = RollbackExecutionRequest.all();

            assertThatThrownBy(() -> service.rollbackExecution(executionId, request, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("could not be claimed for rollback");

            verify(musicDestination, never()).removeTrackFromPlaylist(any(), any());
        }

        @Test
        @DisplayName("Concurrency: Two simultaneous rollback claims for same track: second claim fails and does NOT invoke provider")
        void twoSimultaneousRollbackClaimsForSameTrack_secondClaimFailsAndDoesNotCallProvider() {
            ExecutionTrackRecord track0 = execution.getTrackRecords().get(0);
            track0.markAdding();
            track0.markAdded("yt_item_0");

            execution.reconcileCounts();
            execution.setStatus(MigrationExecutionStatus.COMPLETED);

            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId))
                    .thenReturn(List.of(track0));

            // Execution claim succeeds
            when(executionRepository.claimExecutionRunningWithVersion(any(), any(), anyLong(), any()))
                    .thenReturn(1);

            // But track-level atomic claim returns 0 (already claimed ROLLING_BACK by concurrent instance)
            when(trackRecordRepository.claimTrackRollingBack(eq(track0.getId()), any()))
                    .thenReturn(0);

            RollbackExecutionRequest request = RollbackExecutionRequest.all();
            ExecutionRollbackSummary summary = service.rollbackExecution(executionId, request, userId);

            assertThat(summary.overallStatus()).isEqualTo(RollbackExecutionStatus.FAILED);
            assertThat(summary.failedRollbacks()).isEqualTo(1);
            assertThat(summary.trackOutcomes()).hasSize(1);
            assertThat(summary.trackOutcomes().get(0).message()).contains("concurrently claimed");

            // CRITICAL: remote provider MUST NOT be called!
            verify(musicDestination, never()).removeTrackFromPlaylist(any(), any());
        }
    }
}
