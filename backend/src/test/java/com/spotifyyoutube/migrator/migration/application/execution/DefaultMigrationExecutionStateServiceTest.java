package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItem;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.InitializeExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.InsertTracksExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Phase 11E: DefaultMigrationExecutionStateService Test")
class DefaultMigrationExecutionStateServiceTest {

    private MigrationExecutionRepository executionRepository;
    private ExecutionTrackRecordRepository trackRecordRepository;
    private ReviewSessionRepository reviewSessionRepository;
    private ApprovedMigrationExecutionService executionService;
    private MusicDestination musicDestination;
    private TransactionTemplate transactionTemplate;

    private DefaultMigrationExecutionStateService service;

    private UUID userId;
    private User testUser;
    private UUID sessionId;
    private ReviewSession reviewSession;
    private ApprovedMigrationContext approvedContext;

    @BeforeEach
    void setUp() {
        executionRepository = mock(MigrationExecutionRepository.class);
        trackRecordRepository = mock(ExecutionTrackRecordRepository.class);
        reviewSessionRepository = mock(ReviewSessionRepository.class);
        executionService = mock(ApprovedMigrationExecutionService.class);
        musicDestination = mock(MusicDestination.class);
        transactionTemplate = mock(TransactionTemplate.class);

        // Configure TransactionTemplate mock to execute callbacks directly
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        when(executionRepository.claimExecutionRunningWithVersion(any(), any(), anyLong(), any())).thenReturn(1);

        service = new DefaultMigrationExecutionStateService(
                executionRepository,
                trackRecordRepository,
                reviewSessionRepository,
                executionService,
                musicDestination,
                transactionTemplate
        );

        userId = UUID.randomUUID();
        testUser = new User("tester@example.com");
        testUser.setId(userId);

        sessionId = UUID.randomUUID();
        reviewSession = new ReviewSession(testUser, "sp_playlist_1", "Source Playlist", UUID.randomUUID());
        reviewSession.setId(sessionId);
        reviewSession.setStatus(ReviewSessionStatus.COMPLETED);

        ApprovedTrack track0 = new ApprovedTrack(
                0,
                "sp_t0",
                "Song Zero",
                "Artist A",
                "yt_t0",
                "Song Zero (Audio)",
                "Channel A",
                Duration.ofMinutes(3),
                ReviewDecisionSource.AUTOMATIC,
                0
        );

        ApprovedTrack track1 = new ApprovedTrack(
                1,
                "sp_t1",
                "Song One",
                "Artist B",
                "yt_t1",
                "Song One (Audio)",
                "Channel B",
                Duration.ofMinutes(4),
                ReviewDecisionSource.USER_SELECTED,
                1
        );

        approvedContext = new ApprovedMigrationContext(
                sessionId,
                UUID.randomUUID(),
                userId,
                "sp_playlist_1",
                "Source Playlist",
                "Target Playlist",
                "Description",
                false,
                List.of(track0, track1),
                2,
                2,
                0,
                Instant.now()
        );
    }

    @Nested
    @DisplayName("Execution Initialization Tests")
    class InitializationTests {

        @Test
        @DisplayName("initializeExecution validates context, creates execution record, and persists tracks")
        void initializeSuccess() {
            InitializeExecutionRequest req = new InitializeExecutionRequest(
                    sessionId,
                    "PL_dest_123",
                    "https://music.youtube.com/playlist?list=PL_dest_123",
                    userId
            );

            when(executionRepository.findByReviewSessionId(sessionId)).thenReturn(Optional.empty());
            when(executionService.prepareExecution(sessionId, userId)).thenReturn(approvedContext);
            when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(reviewSession));
            when(musicDestination.getPlaylist(userId, "PL_dest_123"))
                    .thenReturn(new DestinationPlaylist("PL_dest_123", "Target Playlist", null, "private", "https://music.youtube.com/playlist?list=PL_dest_123", Platform.YOUTUBE));

            when(executionRepository.saveAndFlush(any(MigrationExecution.class))).thenAnswer(inv -> {
                MigrationExecution exec = inv.getArgument(0);
                exec.setId(UUID.randomUUID());
                return exec;
            });

            MigrationExecutionSummary summary = service.initializeExecution(req, userId);

            assertThat(summary).isNotNull();
            assertThat(summary.destinationPlaylistId()).isEqualTo("PL_dest_123");
            assertThat(summary.status()).isEqualTo(MigrationExecutionStatus.INITIALIZED);
            assertThat(summary.totalApprovedTracks()).isEqualTo(2);
            assertThat(summary.canResume()).isTrue();
            assertThat(summary.trackRecords()).hasSize(2);

            verify(executionRepository).saveAndFlush(any(MigrationExecution.class));
        }

        @Test
        @DisplayName("initializeExecution is idempotent when called with matching destination playlist")
        void initializeIdempotentSuccess() {
            InitializeExecutionRequest req = new InitializeExecutionRequest(
                    sessionId,
                    "PL_dest_123",
                    "https://music.youtube.com/playlist?list=PL_dest_123",
                    userId
            );

            MigrationExecution existing = new MigrationExecution(
                    testUser,
                    reviewSession,
                    approvedContext,
                    "PL_dest_123",
                    "https://music.youtube.com/playlist?list=PL_dest_123"
            );
            existing.setId(UUID.randomUUID());

            when(executionRepository.findByReviewSessionId(sessionId)).thenReturn(Optional.of(existing));

            MigrationExecutionSummary summary = service.initializeExecution(req, userId);

            assertThat(summary).isNotNull();
            assertThat(summary.executionId()).isEqualTo(existing.getId());
            verify(executionRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("initializeExecution throws InvalidStateException if existing execution has different destination playlist")
        void initializeConflictOnDifferentPlaylist() {
            InitializeExecutionRequest req = new InitializeExecutionRequest(
                    sessionId,
                    "PL_different_456",
                    null,
                    userId
            );

            MigrationExecution existing = new MigrationExecution(
                    testUser,
                    reviewSession,
                    approvedContext,
                    "PL_dest_123",
                    null
            );
            existing.setId(UUID.randomUUID());

            when(executionRepository.findByReviewSessionId(sessionId)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.initializeExecution(req, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("already bound to destination playlist");
        }

        @Test
        @DisplayName("initializeExecution throws IllegalArgumentException when caller attempts user impersonation")
        void initializeRejectsImpersonation() {
            UUID otherUser = UUID.randomUUID();
            InitializeExecutionRequest req = new InitializeExecutionRequest(
                    sessionId,
                    "PL_dest_123",
                    null,
                    otherUser
            );

            assertThatThrownBy(() -> service.initializeExecution(req, userId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match authenticated user");
        }

        @Test
        @DisplayName("initializeExecution throws ResourceNotFoundException when destination playlist is inaccessible")
        void initializeRejectsMissingPlaylist() {
            InitializeExecutionRequest req = new InitializeExecutionRequest(
                    sessionId,
                    "PL_missing_999",
                    null,
                    userId
            );

            when(executionRepository.findByReviewSessionId(sessionId)).thenReturn(Optional.empty());
            when(executionService.prepareExecution(sessionId, userId)).thenReturn(approvedContext);
            when(reviewSessionRepository.findById(sessionId)).thenReturn(Optional.of(reviewSession));
            when(musicDestination.getPlaylist(userId, "PL_missing_999"))
                    .thenThrow(new ResourceNotFoundException("Playlist not found"));

            assertThatThrownBy(() -> service.initializeExecution(req, userId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Destination playlist not found or inaccessible");
        }
    }

    @Nested
    @DisplayName("Execution Query Tests (Anti-IDOR and Retrieval)")
    class QueryTests {

        @Test
        @DisplayName("getExecution returns summary for owner")
        void getExecutionSuccess() {
            UUID execId = UUID.randomUUID();
            MigrationExecution exec = new MigrationExecution(
                    testUser,
                    reviewSession,
                    approvedContext,
                    "PL_dest_123",
                    null
            );
            exec.setId(execId);

            when(executionRepository.findById(execId)).thenReturn(Optional.of(exec));

            MigrationExecutionSummary summary = service.getExecution(execId, userId);

            assertThat(summary).isNotNull();
            assertThat(summary.executionId()).isEqualTo(execId);
            assertThat(summary.userId()).isEqualTo(userId);
            assertThat(summary.trackRecords()).hasSize(2);
        }

        @Test
        @DisplayName("getExecution throws ResourceNotFoundException when user does not own execution (Anti-IDOR)")
        void getExecutionAntiIdor() {
            UUID execId = UUID.randomUUID();
            UUID otherUserId = UUID.randomUUID();
            User otherUser = new User("other@example.com");
            otherUser.setId(otherUserId);

            MigrationExecution exec = new MigrationExecution(
                    otherUser,
                    reviewSession,
                    approvedContext,
                    "PL_dest_123",
                    null
            );
            exec.setId(execId);

            when(executionRepository.findById(execId)).thenReturn(Optional.of(exec));

            assertThatThrownBy(() -> service.getExecution(execId, userId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Migration execution not found");
        }

        @Test
        @DisplayName("getExecutionByReviewSession returns summary for owner")
        void getExecutionByReviewSessionSuccess() {
            UUID execId = UUID.randomUUID();
            MigrationExecution exec = new MigrationExecution(
                    testUser,
                    reviewSession,
                    approvedContext,
                    "PL_dest_123",
                    null
            );
            exec.setId(execId);

            when(executionRepository.findByReviewSessionId(sessionId)).thenReturn(Optional.of(exec));

            MigrationExecutionSummary summary = service.getExecutionByReviewSession(sessionId, userId);

            assertThat(summary).isNotNull();
            assertThat(summary.executionId()).isEqualTo(execId);
            assertThat(summary.userId()).isEqualTo(userId);
            assertThat(summary.trackRecords()).hasSize(2);
        }

        @Test
        @DisplayName("getExecutionByReviewSession throws ResourceNotFoundException when user does not own execution (Anti-IDOR)")
        void getExecutionByReviewSessionAntiIdor() {
            UUID otherUserId = UUID.randomUUID();
            User otherUser = new User("other@example.com");
            otherUser.setId(otherUserId);

            MigrationExecution exec = new MigrationExecution(
                    otherUser,
                    reviewSession,
                    approvedContext,
                    "PL_dest_123",
                    null
            );
            exec.setId(UUID.randomUUID());

            when(executionRepository.findByReviewSessionId(sessionId)).thenReturn(Optional.of(exec));

            assertThatThrownBy(() -> service.getExecutionByReviewSession(sessionId, userId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Migration execution not found for review session");
        }
    }

    @Nested
    @DisplayName("Track Insertion & Safe Resume Tests")
    class InsertionAndResumeTests {

        @Test
        @DisplayName("executeTrackInsertion successfully inserts all tracks in canonical order")
        void executeTrackInsertionSuccess() {
            UUID execId = UUID.randomUUID();
            MigrationExecution exec = new MigrationExecution(
                    testUser,
                    reviewSession,
                    approvedContext,
                    "PL_dest_123",
                    null
            );
            exec.setId(execId);

            when(executionRepository.findById(execId)).thenReturn(Optional.of(exec));
            when(executionRepository.saveAndFlush(any(MigrationExecution.class))).thenReturn(exec);

            ExecutionTrackRecord r0 = exec.getTrackRecords().get(0);
            r0.setId(UUID.randomUUID());
            ExecutionTrackRecord r1 = exec.getTrackRecords().get(1);
            r1.setId(UUID.randomUUID());

            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(execId))
                    .thenReturn(exec.getTrackRecords());
            when(trackRecordRepository.findById(r0.getId())).thenReturn(Optional.of(r0));
            when(trackRecordRepository.findById(r1.getId())).thenReturn(Optional.of(r1));

            when(musicDestination.addTrackToPlaylist(eq(userId), eq("PL_dest_123"), any(AddTrackToDestinationPlaylistRequest.class)))
                    .thenReturn(new DestinationPlaylistItem("item_0", "PL_dest_123", "yt_t0", 0))
                    .thenReturn(new DestinationPlaylistItem("item_1", "PL_dest_123", "yt_t1", 1));

            MigrationExecutionSummary summary = service.executeTrackInsertion(execId, userId);

            assertThat(summary.fullySuccessful()).isTrue();
            assertThat(summary.totalApprovedTracks()).isEqualTo(2);
            assertThat(summary.successfulInsertions()).isEqualTo(2);
            assertThat(summary.failedInsertions()).isZero();
            assertThat(exec.getStatus()).isEqualTo(MigrationExecutionStatus.COMPLETED);
        }

        @Test
        @DisplayName("executeTrackInsertion stops on provider rejection, records FAILED, leaves remainder PENDING")
        void executeTrackInsertionStopsOnRejection() {
            UUID execId = UUID.randomUUID();
            MigrationExecution exec = new MigrationExecution(
                    testUser,
                    reviewSession,
                    approvedContext,
                    "PL_dest_123",
                    null
            );
            exec.setId(execId);

            when(executionRepository.findById(execId)).thenReturn(Optional.of(exec));
            when(executionRepository.saveAndFlush(any(MigrationExecution.class))).thenReturn(exec);

            ExecutionTrackRecord r0 = exec.getTrackRecords().get(0);
            r0.setId(UUID.randomUUID());
            ExecutionTrackRecord r1 = exec.getTrackRecords().get(1);
            r1.setId(UUID.randomUUID());

            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(execId))
                    .thenReturn(exec.getTrackRecords());
            when(trackRecordRepository.findById(r0.getId())).thenReturn(Optional.of(r0));
            when(trackRecordRepository.findById(r1.getId())).thenReturn(Optional.of(r1));

            when(musicDestination.addTrackToPlaylist(eq(userId), eq("PL_dest_123"), any(AddTrackToDestinationPlaylistRequest.class)))
                    .thenReturn(new DestinationPlaylistItem("item_0", "PL_dest_123", "yt_t0", 0))
                    .thenThrow(new ExternalProviderException("YouTube", 403, "Quota exceeded"));

            MigrationExecutionSummary summary = service.executeTrackInsertion(execId, userId);

            assertThat(summary.fullySuccessful()).isFalse();
            assertThat(summary.successfulInsertions()).isEqualTo(1);
            assertThat(summary.failedInsertions()).isEqualTo(1);

            assertThat(r0.getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
            assertThat(r1.getStatus()).isEqualTo(ExecutionTrackStatus.FAILED);
            assertThat(r1.getErrorMessage()).contains("Quota exceeded");
            assertThat(exec.getStatus()).isEqualTo(MigrationExecutionStatus.PARTIALLY_COMPLETED);
        }

        @Test
        @DisplayName("executeTrackInsertion records UNCERTAIN and stops on network timeout (ResourceAccessException)")
        void executeTrackInsertionRecordsUncertainOnTimeout() {
            UUID execId = UUID.randomUUID();
            MigrationExecution exec = new MigrationExecution(
                    testUser,
                    reviewSession,
                    approvedContext,
                    "PL_dest_123",
                    null
            );
            exec.setId(execId);

            when(executionRepository.findById(execId)).thenReturn(Optional.of(exec));
            when(executionRepository.saveAndFlush(any(MigrationExecution.class))).thenReturn(exec);

            ExecutionTrackRecord r0 = exec.getTrackRecords().get(0);
            r0.setId(UUID.randomUUID());
            ExecutionTrackRecord r1 = exec.getTrackRecords().get(1);
            r1.setId(UUID.randomUUID());

            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(execId))
                    .thenReturn(exec.getTrackRecords());
            when(trackRecordRepository.findById(r0.getId())).thenReturn(Optional.of(r0));
            when(trackRecordRepository.findById(r1.getId())).thenReturn(Optional.of(r1));

            when(musicDestination.addTrackToPlaylist(eq(userId), eq("PL_dest_123"), any(AddTrackToDestinationPlaylistRequest.class)))
                    .thenThrow(new ResourceAccessException("Read timed out with bearer ya29.secret_token"));

            MigrationExecutionSummary summary = service.executeTrackInsertion(execId, userId);

            assertThat(summary.fullySuccessful()).isFalse();
            assertThat(summary.uncertainInsertions()).isEqualTo(1);

            assertThat(r0.getStatus()).isEqualTo(ExecutionTrackStatus.UNCERTAIN);
            assertThat(r0.getErrorMessage()).doesNotContain("ya29.secret_token");
            assertThat(r0.getErrorMessage()).contains("[REDACTED]");

            assertThat(exec.getStatus()).isEqualTo(MigrationExecutionStatus.RECOVERY_REQUIRED);
        }

        @Test
        @DisplayName("resumeExecution skips ADDED tracks and only attempts remaining PENDING tracks")
        void resumeExecutionSkipsAddedTracks() {
            UUID execId = UUID.randomUUID();
            MigrationExecution exec = new MigrationExecution(
                    testUser,
                    reviewSession,
                    approvedContext,
                    "PL_dest_123",
                    null
            );
            exec.setId(execId);

            // Track 0 was already ADDED in a previous run
            ExecutionTrackRecord r0 = exec.getTrackRecords().get(0);
            r0.setId(UUID.randomUUID());
            r0.markAdding();
            r0.markAdded("item_0");
            exec.setStatus(MigrationExecutionStatus.PARTIALLY_COMPLETED);

            ExecutionTrackRecord r1 = exec.getTrackRecords().get(1);
            r1.setId(UUID.randomUUID());

            when(executionRepository.findById(execId)).thenReturn(Optional.of(exec));
            when(executionRepository.saveAndFlush(any(MigrationExecution.class))).thenReturn(exec);

            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(execId))
                    .thenReturn(exec.getTrackRecords());
            when(trackRecordRepository.findById(r1.getId())).thenReturn(Optional.of(r1));

            when(musicDestination.addTrackToPlaylist(eq(userId), eq("PL_dest_123"), any(AddTrackToDestinationPlaylistRequest.class)))
                    .thenReturn(new DestinationPlaylistItem("item_1", "PL_dest_123", "yt_t1", 1));

            MigrationExecutionSummary summary = service.resumeExecution(execId, userId);

            assertThat(summary.fullySuccessful()).isTrue();
            assertThat(summary.successfulInsertions()).isEqualTo(2);
            assertThat(summary.status()).isEqualTo(MigrationExecutionStatus.COMPLETED);

            // r0 was never re-inserted: only r1 was inserted in the destination call
            verify(musicDestination).addTrackToPlaylist(eq(userId), eq("PL_dest_123"), any(AddTrackToDestinationPlaylistRequest.class));
        }

        @Test
        @DisplayName("resumeExecution rejects when execution is already RUNNING (concurrency conflict)")
        void resumeRejectsWhenRunning() {
            UUID execId = UUID.randomUUID();
            MigrationExecution exec = new MigrationExecution(
                    testUser,
                    reviewSession,
                    approvedContext,
                    "PL_dest_123",
                    null
            );
            exec.setId(execId);
            exec.setStatus(MigrationExecutionStatus.RUNNING);

            when(executionRepository.findById(execId)).thenReturn(Optional.of(exec));

            assertThatThrownBy(() -> service.resumeExecution(execId, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("already in progress by another request");
        }

        @Test
        @DisplayName("resumeExecution rejects when execution is in terminal COMPLETED state")
        void resumeRejectsWhenCompleted() {
            UUID execId = UUID.randomUUID();
            MigrationExecution exec = new MigrationExecution(
                    testUser,
                    reviewSession,
                    approvedContext,
                    "PL_dest_123",
                    null
            );
            exec.setId(execId);
            exec.setStatus(MigrationExecutionStatus.COMPLETED);

            when(executionRepository.findById(execId)).thenReturn(Optional.of(exec));

            assertThatThrownBy(() -> service.resumeExecution(execId, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("terminal status COMPLETED");
        }

        @Test
        @DisplayName("resumeExecution reconciles stale ADDING track to UNCERTAIN and rejects resume without blind retry")
        void resumeReconcilesStaleAddingToUncertain() {
            UUID execId = UUID.randomUUID();
            MigrationExecution exec = new MigrationExecution(
                    testUser,
                    reviewSession,
                    approvedContext,
                    "PL_dest_123",
                    null
            );
            exec.setId(execId);

            ExecutionTrackRecord r0 = exec.getTrackRecords().get(0);
            r0.setId(UUID.randomUUID());
            r0.markAdding();
            // Server crashed before r0 could be marked ADDED or FAILED: remains ADDING

            when(executionRepository.findById(execId)).thenReturn(Optional.of(exec));

            assertThatThrownBy(() -> service.resumeExecution(execId, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("RECOVERY_REQUIRED");

            assertThat(r0.getStatus()).isEqualTo(ExecutionTrackStatus.UNCERTAIN);
            assertThat(exec.getStatus()).isEqualTo(MigrationExecutionStatus.RECOVERY_REQUIRED);
            verify(musicDestination, never()).addTrackToPlaylist(any(), any(), any());
        }
    }
}
