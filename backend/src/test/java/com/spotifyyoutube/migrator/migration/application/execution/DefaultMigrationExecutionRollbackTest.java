package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionRollbackSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackRecordDto;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackTrackOutcomeDto;
import com.spotifyyoutube.migrator.migration.application.execution.failure.ExecutionFailureClassifier;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionRollbackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.RollbackExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.RollbackRecordStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionRollbackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
        reviewSession = new ReviewSession(UUID.randomUUID(), testUser, "sp_pl_rb", "Rollback Test Playlist");
        reviewSession.setId(sessionId);
        reviewSession.setStatus(ReviewSessionStatus.APPROVED);

        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(reviewSession));

        executionId = UUID.randomUUID();
        execution = new MigrationExecution(sessionId, reviewSession.getMigrationPlanId(), userId, "yt_pl_target", 2);
        execution.setId(executionId);
        execution.setStatus(MigrationExecutionStatus.COMPLETED);
        execution.setSuccessfulInsertions(2);
        execution.setFailedInsertions(0);

        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        when(executionRepository.save(any(MigrationExecution.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Nested
    @DisplayName("Partial Rollback Operations")
    class RollbackOperations {

        @Test
        @DisplayName("Successfully rolls back a single confirmed inserted playlist item")
        void rollsBackSingleItemSuccessfully() {
            ExecutionTrackRecord track0 = new ExecutionTrackRecord(execution, "sp_1", "yt_video_1", 0);
            track0.markAdded("yt_item_0");

            ExecutionTrackRecord track1 = new ExecutionTrackRecord(execution, "sp_2", "yt_video_2", 1);
            track1.markAdded("yt_item_1");

            when(trackRecordRepository.findByExecutionIdOrderByCanonicalTargetOrderAsc(executionId))
                    .thenReturn(List.of(track0, track1));
            when(trackRecordRepository.save(any(ExecutionTrackRecord.class))).thenAnswer(i -> i.getArgument(0));
            when(rollbackRecordRepository.save(any(ExecutionRollbackRecord.class))).thenAnswer(i -> i.getArgument(0));

            doNothing().when(musicDestination).removeTrackFromPlaylist(userId, "yt_item_0");

            RollbackExecutionRequest request = new RollbackExecutionRequest(
                    executionId, userId, List.of("yt_item_0"), "Removing first song");

            ExecutionRollbackSummary summary = service.rollbackExecution(request, userId);

            // Verify remote call targeting playlist item ID
            verify(musicDestination, times(1)).removeTrackFromPlaylist(userId, "yt_item_0");
            verify(musicDestination, never()).removeTrackFromPlaylist(userId, "yt_item_1");

            // Verify state mutations
            assertThat(track0.getStatus()).isEqualTo(ExecutionTrackStatus.ROLLED_BACK);
            assertThat(track1.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);

            assertThat(summary.status()).isEqualTo(RollbackExecutionStatus.SUCCESS);
            assertThat(summary.requestedItems()).isEqualTo(1);
            assertThat(summary.successfullyRolledBack()).isEqualTo(1);
            assertThat(summary.failedRollbacks()).isEqualTo(0);
            assertThat(summary.outcomes()).hasSize(1);
            assertThat(summary.outcomes().get(0).status()).isEqualTo(RollbackRecordStatus.ROLLED_BACK);

            // Verify aggregate execution status is PARTIALLY_ROLLED_BACK because track 1 is still ADDED
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.PARTIALLY_ROLLED_BACK);
            assertThat(execution.getRolledBackInsertions()).isEqualTo(1);
        }

        @Test
        @DisplayName("Successfully rolls back all confirmed inserted items when target list is omitted")
        void rollsBackAllConfirmedItemsWhenTargetOmitted() {
            ExecutionTrackRecord track0 = new ExecutionTrackRecord(execution, "sp_1", "yt_video_1", 0);
            track0.markAdded("yt_item_0");

            ExecutionTrackRecord track1 = new ExecutionTrackRecord(execution, "sp_2", "yt_video_2", 1);
            track1.markAdded("yt_item_1");

            when(trackRecordRepository.findByExecutionIdOrderByCanonicalTargetOrderAsc(executionId))
                    .thenReturn(List.of(track0, track1));
            when(trackRecordRepository.save(any(ExecutionTrackRecord.class))).thenAnswer(i -> i.getArgument(0));
            when(rollbackRecordRepository.save(any(ExecutionRollbackRecord.class))).thenAnswer(i -> i.getArgument(0));

            RollbackExecutionRequest request = new RollbackExecutionRequest(
                    executionId, userId, null, "Full rollback of execution");

            ExecutionRollbackSummary summary = service.rollbackExecution(request, userId);

            verify(musicDestination, times(1)).removeTrackFromPlaylist(userId, "yt_item_0");
            verify(musicDestination, times(1)).removeTrackFromPlaylist(userId, "yt_item_1");

            assertThat(track0.getStatus()).isEqualTo(ExecutionTrackStatus.ROLLED_BACK);
            assertThat(track1.getStatus()).isEqualTo(ExecutionTrackStatus.ROLLED_BACK);

            assertThat(summary.status()).isEqualTo(RollbackExecutionStatus.SUCCESS);
            assertThat(summary.successfullyRolledBack()).isEqualTo(2);

            // Both items rolled back -> aggregate status is ROLLED_BACK
            assertThat(execution.getStatus()).isEqualTo(MigrationExecutionStatus.ROLLED_BACK);
            assertThat(execution.getRolledBackInsertions()).isEqualTo(2);
        }

        @Test
        @DisplayName("Handles remote 404 Not Found as confirmed deletion (already deleted remotely)")
        void handles404AsConfirmedDeletion() {
            ExecutionTrackRecord track0 = new ExecutionTrackRecord(execution, "sp_1", "yt_video_1", 0);
            track0.markAdded("yt_item_0");

            when(trackRecordRepository.findByExecutionIdOrderByCanonicalTargetOrderAsc(executionId))
                    .thenReturn(List.of(track0));
            when(trackRecordRepository.save(any(ExecutionTrackRecord.class))).thenAnswer(i -> i.getArgument(0));
            when(rollbackRecordRepository.save(any(ExecutionRollbackRecord.class))).thenAnswer(i -> i.getArgument(0));

            // Remote returns 404
            doThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null))
                    .when(musicDestination).removeTrackFromPlaylist(userId, "yt_item_0");

            RollbackExecutionRequest request = new RollbackExecutionRequest(
                    executionId, userId, List.of("yt_item_0"), "Clean up deleted item");

            ExecutionRollbackSummary summary = service.rollbackExecution(request, userId);

            assertThat(summary.status()).isEqualTo(RollbackExecutionStatus.SUCCESS);
            assertThat(summary.successfullyRolledBack()).isEqualTo(1);
            assertThat(track0.getStatus()).isEqualTo(ExecutionTrackStatus.ROLLED_BACK);
        }

        @Test
        @DisplayName("State-Level Idempotency: Repeating rollback request on already ROLLED_BACK item skips remote call")
        void idempotentRollbackSkipsRemoteCall() {
            ExecutionTrackRecord track0 = new ExecutionTrackRecord(execution, "sp_1", "yt_video_1", 0);
            track0.markAdded("yt_item_0");
            track0.markRolledBack("Previously rolled back");

            when(trackRecordRepository.findByExecutionIdOrderByCanonicalTargetOrderAsc(executionId))
                    .thenReturn(List.of(track0));
            when(rollbackRecordRepository.save(any(ExecutionRollbackRecord.class))).thenAnswer(i -> i.getArgument(0));

            RollbackExecutionRequest request = new RollbackExecutionRequest(
                    executionId, userId, List.of("yt_item_0"), "Repeat rollback attempt");

            ExecutionRollbackSummary summary = service.rollbackExecution(request, userId);

            // Remote call MUST NOT be made
            verify(musicDestination, never()).removeTrackFromPlaylist(any(), any());

            assertThat(summary.status()).isEqualTo(RollbackExecutionStatus.NO_OP);
            assertThat(summary.outcomes()).hasSize(1);
            RollbackTrackOutcomeDto outcome = summary.outcomes().get(0);
            assertThat(outcome.status()).isEqualTo(RollbackRecordStatus.ALREADY_ROLLED_BACK);
        }

        @Test
        @DisplayName("Handles remote failure during rollback gracefully without corrupting state")
        void handlesRemoteFailureDuringRollback() {
            ExecutionTrackRecord track0 = new ExecutionTrackRecord(execution, "sp_1", "yt_video_1", 0);
            track0.markAdded("yt_item_0");

            ExecutionTrackRecord track1 = new ExecutionTrackRecord(execution, "sp_2", "yt_video_2", 1);
            track1.markAdded("yt_item_1");

            when(trackRecordRepository.findByExecutionIdOrderByCanonicalTargetOrderAsc(executionId))
                    .thenReturn(List.of(track0, track1));
            when(trackRecordRepository.save(any(ExecutionTrackRecord.class))).thenAnswer(i -> i.getArgument(0));
            when(rollbackRecordRepository.save(any(ExecutionRollbackRecord.class))).thenAnswer(i -> i.getArgument(0));

            // Track 0 succeeds, Track 1 fails on remote
            doNothing().when(musicDestination).removeTrackFromPlaylist(userId, "yt_item_0");
            doThrow(new ExternalProviderException("YouTube API 500 Internal Server Error", true))
                    .when(musicDestination).removeTrackFromPlaylist(userId, "yt_item_1");

            RollbackExecutionRequest request = new RollbackExecutionRequest(
                    executionId, userId, List.of("yt_item_0", "yt_item_1"), "Partial failure rollback");

            ExecutionRollbackSummary summary = service.rollbackExecution(request, userId);

            assertThat(summary.status()).isEqualTo(RollbackExecutionStatus.PARTIAL_SUCCESS);
            assertThat(summary.successfullyRolledBack()).isEqualTo(1);
            assertThat(summary.failedRollbacks()).isEqualTo(1);

            // Track 0 was rolled back, Track 1 remains ADDED
            assertThat(track0.getStatus()).isEqualTo(ExecutionTrackStatus.ROLLED_BACK);
            assertThat(track1.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);

            // Audit records: 1 ROLLED_BACK, 1 FAILED
            verify(rollbackRecordRepository, times(2)).save(any(ExecutionRollbackRecord.class));
        }
    }

    @Nested
    @DisplayName("Rollback Safety & Validation Guardrails")
    class SafetyAndValidation {

        @Test
        @DisplayName("Rejects rollback if requested playlist item does not belong to the execution")
        void rejectsRollbackOfUnrecognizedPlaylistItem() {
            ExecutionTrackRecord track0 = new ExecutionTrackRecord(execution, "sp_1", "yt_video_1", 0);
            track0.markAdded("yt_item_0");

            when(trackRecordRepository.findByExecutionIdOrderByCanonicalTargetOrderAsc(executionId))
                    .thenReturn(List.of(track0));

            RollbackExecutionRequest request = new RollbackExecutionRequest(
                    executionId, userId, List.of("foreign_playlist_item_999"), "Malicious rollback");

            assertThatThrownBy(() -> service.rollbackExecution(request, userId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not belong to execution");

            verify(musicDestination, never()).removeTrackFromPlaylist(any(), any());
        }

        @Test
        @DisplayName("Rejects rollback if target track was never inserted (status PENDING or FAILED without playlistItemId)")
        void rejectsRollbackOfUninsertedTrack() {
            ExecutionTrackRecord track0 = new ExecutionTrackRecord(execution, "sp_1", "yt_video_1", 0);
            track0.markFailed("Failed before insertion");

            when(trackRecordRepository.findByExecutionIdOrderByCanonicalTargetOrderAsc(executionId))
                    .thenReturn(List.of(track0));

            RollbackExecutionRequest request = new RollbackExecutionRequest(
                    executionId, userId, null, "Rollback uninserted track");

            assertThatThrownBy(() -> service.rollbackExecution(request, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("No confirmed inserted tracks eligible for rollback");

            verify(musicDestination, never()).removeTrackFromPlaylist(any(), any());
        }

        @Test
        @DisplayName("Rejects rollback if execution is currently RUNNING")
        void rejectsRollbackWhenRunning() {
            execution.setStatus(MigrationExecutionStatus.RUNNING);

            RollbackExecutionRequest request = new RollbackExecutionRequest(
                    executionId, userId, List.of("yt_item_0"), "Rollback while running");

            assertThatThrownBy(() -> service.rollbackExecution(request, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("RUNNING");

            verify(musicDestination, never()).removeTrackFromPlaylist(any(), any());
        }

        @Test
        @DisplayName("Anti-IDOR: Rejects rollback if user ID does not match execution owner")
        void rejectsCrossUserRollback() {
            UUID intruderId = UUID.randomUUID();
            RollbackExecutionRequest request = new RollbackExecutionRequest(
                    executionId, intruderId, List.of("yt_item_0"), "IDOR attempt");

            assertThatThrownBy(() -> service.rollbackExecution(request, intruderId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("MigrationExecution not found");

            verify(musicDestination, never()).removeTrackFromPlaylist(any(), any());
        }

        @Test
        @DisplayName("Retrieves rollback audit history ordered by attemptedAt descending")
        void retrievesRollbackHistory() {
            ExecutionRollbackRecord r1 = new ExecutionRollbackRecord(
                    executionId, UUID.randomUUID(), "yt_item_0", RollbackRecordStatus.ROLLED_BACK, "User request");
            ExecutionRollbackRecord r2 = new ExecutionRollbackRecord(
                    executionId, UUID.randomUUID(), "yt_item_1", RollbackRecordStatus.FAILED, "YouTube 500 error");

            when(rollbackRecordRepository.findByExecutionIdOrderByAttemptedAtDesc(executionId))
                    .thenReturn(List.of(r1, r2));

            List<RollbackRecordDto> history = service.getRollbackHistory(executionId, userId);

            assertThat(history).hasSize(2);
            assertThat(history.get(0).destinationPlaylistItemId()).isEqualTo("yt_item_0");
            assertThat(history.get(0).status()).isEqualTo(RollbackRecordStatus.ROLLED_BACK);
            assertThat(history.get(1).destinationPlaylistItemId()).isEqualTo("yt_item_1");
            assertThat(history.get(1).status()).isEqualTo(RollbackRecordStatus.FAILED);
        }
    }
}
