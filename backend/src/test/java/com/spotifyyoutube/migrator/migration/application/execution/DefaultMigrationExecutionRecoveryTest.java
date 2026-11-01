package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItem;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RecoverExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.failure.ExecutionFailureClassifier;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Phase 11F: DefaultMigrationExecutionRecoveryTest")
class DefaultMigrationExecutionRecoveryTest {

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
        testUser = new User("recovery-tester@example.com");
        testUser.setId(userId);

        sessionId = UUID.randomUUID();
        reviewSession = new ReviewSession(UUID.randomUUID(), testUser, "sp_pl_rec", "Recovery Test Playlist");
        reviewSession.setId(sessionId);
        reviewSession.setStatus(ReviewSessionStatus.APPROVED);

        approvedContext = new ApprovedMigrationContext(
                sessionId,
                reviewSession.getMigrationPlanId(),
                userId,
                "sp_pl_rec",
                "Recovery Test Playlist",
                List.of(
                        new ApprovedTrack("sp_1", "Song One", "Artist A", "yt_video_1", 0, ReviewDecisionSource.MANUAL),
                        new ApprovedTrack("sp_2", "Song Two", "Artist B", "yt_video_2", 1, ReviewDecisionSource.AUTO)
                ),
                Instant.now()
        );

        when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(reviewSession));
        when(executionService.loadApprovedMigrationContext(sessionId, userId)).thenReturn(approvedContext);

        executionId = UUID.randomUUID();
        execution = new MigrationExecution(sessionId, reviewSession.getMigrationPlanId(), userId, "yt_pl_target", 2);
        execution.setId(executionId);
        execution.setStatus(MigrationExecutionStatus.PARTIALLY_COMPLETED);
        execution.setFailedInsertions(1);
        execution.setSuccessfulInsertions(1);

        when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        when(executionRepository.save(any(MigrationExecution.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Nested
    @DisplayName("Eligible Recovery Execution")
    class EligibleRecoveryTests {

        @Test
        @DisplayName("Successfully recovers eligible FAILED track while strictly preserving ADDED track")
        void recoversFailedTrackPreservingAddedTrack() {
            ExecutionTrackRecord track0 = new ExecutionTrackRecord(execution, "sp_1", "yt_video_1", 0);
            track0.markAdded("yt_item_0");

            ExecutionTrackRecord track1 = new ExecutionTrackRecord(execution, "sp_2", "yt_video_2", 1);
            track1.markFailed("Network timeout on first attempt");

            when(trackRecordRepository.findByExecutionIdOrderByCanonicalTargetOrderAsc(executionId))
                    .thenReturn(List.of(track0, track1));
            when(trackRecordRepository.save(any(ExecutionTrackRecord.class))).thenAnswer(i -> i.getArgument(0));

            when(musicDestination.addTrackToPlaylist(
                    eq(userId),
                    argThat(req -> "yt_video_2".equals(req.youtubeTrackId()) && req.position() == 1)
            )).thenReturn(new DestinationPlaylistItem("yt_item_1", "yt_pl_target", "yt_video_2", 1));

            RecoverExecutionRequest request = new RecoverExecutionRequest(executionId, userId, "Retrying failed track");
            MigrationExecutionSummary summary = service.recoverExecution(request, userId);

            // Verify MusicDestination was called ONLY for track 1
            verify(musicDestination, times(1)).addTrackToPlaylist(eq(userId), any(AddTrackToDestinationPlaylistRequest.class));
            verify(musicDestination, never()).addTrackToPlaylist(
                    eq(userId),
                    argThat(req -> "yt_video_1".equals(req.youtubeTrackId()))
            );

            assertThat(track1.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
            assertThat(track1.getDestinationPlaylistItemId()).isEqualTo("yt_item_1");

            assertThat(summary.status()).isEqualTo(MigrationExecutionStatus.COMPLETED);
            assertThat(summary.successfulInsertions()).isEqualTo(2);
            assertThat(summary.failedInsertions()).isEqualTo(0);
            assertThat(summary.pendingInsertions()).isEqualTo(0);
        }

        @Test
        @DisplayName("Preserves canonical destination ordering during recovery")
        void preservesCanonicalOrderingDuringRecovery() {
            ExecutionTrackRecord track0 = new ExecutionTrackRecord(execution, "sp_1", "yt_video_1", 0);
            track0.markFailed("Rate limit");

            ExecutionTrackRecord track1 = new ExecutionTrackRecord(execution, "sp_2", "yt_video_2", 1);
            track1.markAdded("yt_item_1");

            when(trackRecordRepository.findByExecutionIdOrderByCanonicalTargetOrderAsc(executionId))
                    .thenReturn(List.of(track0, track1));
            when(trackRecordRepository.save(any(ExecutionTrackRecord.class))).thenAnswer(i -> i.getArgument(0));

            when(musicDestination.addTrackToPlaylist(
                    eq(userId),
                    argThat(req -> "yt_video_1".equals(req.youtubeTrackId()) && req.position() == 0)
            )).thenReturn(new DestinationPlaylistItem("yt_item_0", "yt_pl_target", "yt_video_1", 0));

            RecoverExecutionRequest request = new RecoverExecutionRequest(executionId, userId, "Recover first track");
            MigrationExecutionSummary summary = service.recoverExecution(request, userId);

            verify(musicDestination, times(1)).addTrackToPlaylist(
                    eq(userId),
                    argThat(req -> req.position() == 0 && "yt_video_1".equals(req.youtubeTrackId()))
            );

            assertThat(summary.status()).isEqualTo(MigrationExecutionStatus.COMPLETED);
            assertThat(summary.successfulInsertions()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Safety & Guardrails Validation")
    class SafetyGuardrailTests {

        @Test
        @DisplayName("Rejects recovery if execution is already COMPLETED")
        void rejectsRecoveryOfCompletedExecution() {
            execution.setStatus(MigrationExecutionStatus.COMPLETED);
            execution.setSuccessfulInsertions(2);
            execution.setFailedInsertions(0);

            RecoverExecutionRequest request = new RecoverExecutionRequest(executionId, userId, "Invalid retry");

            assertThatThrownBy(() -> service.recoverExecution(request, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("COMPLETED");

            verify(musicDestination, never()).addTrackToPlaylist(any(), any());
        }

        @Test
        @DisplayName("Rejects recovery if execution contains UNCERTAIN tracks")
        void rejectsRecoveryWithUncertainTracks() {
            execution.setStatus(MigrationExecutionStatus.RECOVERY_REQUIRED);
            execution.setUncertainInsertions(1);

            ExecutionTrackRecord track0 = new ExecutionTrackRecord(execution, "sp_1", "yt_video_1", 0);
            track0.markUncertain("Socket timeout during POST");
            when(trackRecordRepository.findByExecutionIdOrderByCanonicalTargetOrderAsc(executionId))
                    .thenReturn(List.of(track0));

            RecoverExecutionRequest request = new RecoverExecutionRequest(executionId, userId, "Retry uncertain");

            assertThatThrownBy(() -> service.recoverExecution(request, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("UNCERTAIN");

            verify(musicDestination, never()).addTrackToPlaylist(any(), any());
        }

        @Test
        @DisplayName("Rejects recovery if execution is currently RUNNING (concurrent operation guard)")
        void rejectsRecoveryWhenCurrentlyRunning() {
            execution.setStatus(MigrationExecutionStatus.RUNNING);

            RecoverExecutionRequest request = new RecoverExecutionRequest(executionId, userId, "Concurrent recovery");

            assertThatThrownBy(() -> service.recoverExecution(request, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("RUNNING");

            verify(musicDestination, never()).addTrackToPlaylist(any(), any());
        }

        @Test
        @DisplayName("Anti-IDOR: Rejects recovery if user ID does not match execution owner")
        void rejectsCrossUserRecovery() {
            UUID otherUserId = UUID.randomUUID();
            RecoverExecutionRequest request = new RecoverExecutionRequest(executionId, otherUserId, "Cross user attack");

            assertThatThrownBy(() -> service.recoverExecution(request, otherUserId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("MigrationExecution not found");

            verify(musicDestination, never()).addTrackToPlaylist(any(), any());
        }

        @Test
        @DisplayName("Releases execution lock and persists accurate PARTIALLY_COMPLETED status if recovered track fails again")
        void updatesStateAccuratelyWhenRecoveryTrackFailsAgain() {
            ExecutionTrackRecord track0 = new ExecutionTrackRecord(execution, "sp_1", "yt_video_1", 0);
            track0.markAdded("yt_item_0");

            ExecutionTrackRecord track1 = new ExecutionTrackRecord(execution, "sp_2", "yt_video_2", 1);
            track1.markFailed("First failure");

            when(trackRecordRepository.findByExecutionIdOrderByCanonicalTargetOrderAsc(executionId))
                    .thenReturn(List.of(track0, track1));
            when(trackRecordRepository.save(any(ExecutionTrackRecord.class))).thenAnswer(i -> i.getArgument(0));

            when(musicDestination.addTrackToPlaylist(any(), any()))
                    .thenThrow(new ExternalProviderException("YouTube API quota exceeded", false));

            RecoverExecutionRequest request = new RecoverExecutionRequest(executionId, userId, "Retry will fail again");
            MigrationExecutionSummary summary = service.recoverExecution(request, userId);

            assertThat(summary.status()).isEqualTo(MigrationExecutionStatus.PARTIALLY_COMPLETED);
            assertThat(summary.successfulInsertions()).isEqualTo(1);
            assertThat(summary.failedInsertions()).isEqualTo(1);
            assertThat(track1.getStatus()).isEqualTo(ExecutionTrackStatus.FAILED);
            assertThat(track1.getFailureReason()).contains("YouTube API quota exceeded");
        }
    }
}
