package com.spotifyyoutube.migrator.migration.reliability.scheduling;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItem;
import com.spotifyyoutube.migrator.migration.application.execution.ApprovedMigrationExecutionService;
import com.spotifyyoutube.migrator.migration.application.execution.DefaultMigrationExecutionStateService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.application.execution.failure.ExecutionFailureClassifier;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.reliability.classification.ProviderFailureClassifier;
import com.spotifyyoutube.migrator.migration.reliability.policy.DefaultRetryPolicy;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Phase 13D-E: Durable Retry Scheduling Unit Test")
class DurableRetrySchedulingTest {

    private MigrationExecutionRepository executionRepository;
    private ExecutionTrackRecordRepository trackRecordRepository;
    private ReviewSessionRepository reviewSessionRepository;
    private ApprovedMigrationExecutionService executionService;
    private MusicDestination musicDestination;
    private TransactionTemplate transactionTemplate;
    private ProviderFailureClassifier failureClassifier;
    private DefaultRetryPolicy retryPolicy;
    private DurableLeaseHeartbeatService heartbeatService;
    private DurableSchedulerProperties properties;

    private DefaultMigrationExecutionStateService service;

    private UUID userId;
    private User testUser;
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
        failureClassifier = new ProviderFailureClassifier();
        retryPolicy = new DefaultRetryPolicy();
        properties = new DurableSchedulerProperties();
        heartbeatService = mock(DurableLeaseHeartbeatService.class);

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        service = new DefaultMigrationExecutionStateService(
                executionRepository,
                trackRecordRepository,
                null,
                null,
                reviewSessionRepository,
                executionService,
                musicDestination,
                new ExecutionFailureClassifier(),
                transactionTemplate,
                failureClassifier,
                retryPolicy,
                heartbeatService,
                properties
        );

        userId = UUID.randomUUID();
        testUser = new User("tester@example.com");
        testUser.setId(userId);

        reviewSession = new ReviewSession(testUser, "sp_pl_1", "Source Playlist", UUID.randomUUID());
        reviewSession.setId(UUID.randomUUID());
        reviewSession.setStatus(ReviewSessionStatus.COMPLETED);

        ApprovedTrack t0 = new ApprovedTrack(
                0, "sp_0", "Title 0", "Artist 0", "yt_0", "YT 0", "Ch 0",
                Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 0
        );
        approvedContext = new ApprovedMigrationContext(
                reviewSession.getId(),
                UUID.randomUUID(),
                userId,
                "sp_playlist_1",
                "Source Playlist",
                "Target Playlist",
                "Description",
                false,
                List.of(t0),
                1,
                1,
                0,
                Instant.now()
        );
    }

    private MigrationExecution createRunningExecution(UUID execId, long generation) {
        MigrationExecution exec = new MigrationExecution(
                testUser, reviewSession, approvedContext, "PL_dest_1", "https://youtube.com/playlist?list=PL_dest_1"
        );
        exec.setId(execId);
        exec.markQueued();
        exec.acquireLease("worker-1", Instant.now().plusSeconds(60), Instant.now());
        return exec;
    }

    private ExecutionTrackRecord createTrackRecord(UUID trackId, MigrationExecution exec, int targetPos) {
        ApprovedTrack at = new ApprovedTrack(
                targetPos, "sp_" + targetPos, "Title " + targetPos, "Artist " + targetPos,
                "yt_" + targetPos, "YT " + targetPos, "Ch " + targetPos,
                Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, targetPos
        );
        ExecutionTrackRecord rec = new ExecutionTrackRecord(exec, at);
        rec.setId(trackId);
        return rec;
    }

    @Nested
    @DisplayName("Durable Retry Persistence and Policy Routing")
    class RetryPolicyRoutingTests {

        @Test
        @DisplayName("Rate limit 429 with excessive Retry-After (>2s) schedules deferred retry and sets QUEUED")
        void rateLimitDeferredRetry() {
            UUID execId = UUID.randomUUID();
            long gen = 1L;
            MigrationExecution exec = createRunningExecution(execId, gen);

            UUID trackId = UUID.randomUUID();
            ExecutionTrackRecord track = createTrackRecord(trackId, exec, 0);

            when(executionRepository.findById(execId)).thenReturn(Optional.of(exec));
            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(execId)).thenReturn(List.of(track));
            when(trackRecordRepository.findDuePendingTracks(execId)).thenReturn(List.of(track));
            when(trackRecordRepository.claimTrackAddingWithFencing(trackId, execId, gen)).thenReturn(1);

            // Provider throws 429 with Retry-After: 30
            ExternalProviderException rateLimitEx = new ExternalProviderException(
                    "YouTube rate limit exceeded: quota", 429, "Retry-After: 30"
            );
            when(musicDestination.addTrackToPlaylist(
                    eq(userId), eq("PL_dest_1"), any(AddTrackToDestinationPlaylistRequest.class)))
                    .thenThrow(rateLimitEx);

            when(trackRecordRepository.scheduleTrackRetryWithFencing(
                    eq(trackId), eq(execId), eq(gen), any(Instant.class), anyString(), anyLong()
            )).thenReturn(1);

            Instant expectedDeferred = Instant.now().plusSeconds(30);
            when(trackRecordRepository.findEarliestDeferredRetry(execId))
                    .thenReturn(Optional.of(expectedDeferred));
            when(executionRepository.releaseLease(
                    eq(execId), eq("worker-1"), eq(gen), eq(MigrationExecutionStatus.QUEUED), eq(expectedDeferred)))
                    .thenReturn(1);

            MigrationExecutionSummary summary = service.executeClaimedTrackInsertion(execId, userId, gen);

            assertThat(summary).isNotNull();
            verify(trackRecordRepository).scheduleTrackRetryWithFencing(
                    eq(trackId), eq(execId), eq(gen), any(Instant.class), eq("RATE_LIMITED"), anyLong()
            );
            verify(executionRepository).releaseLease(
                    eq(execId), eq("worker-1"), eq(gen), eq(MigrationExecutionStatus.QUEUED), eq(expectedDeferred)
            );
            verify(trackRecordRepository, never()).updateTrackStatusWithFencing(
                    any(), any(), anyLong(), eq(ExecutionTrackStatus.ADDED), any(), any(), any()
            );
        }

        @Test
        @DisplayName("Transport timeout on mutating write marks track UNCERTAIN and does not retry blindly")
        void transportTimeoutMarksUncertain() {
            UUID execId = UUID.randomUUID();
            long gen = 1L;
            MigrationExecution exec = createRunningExecution(execId, gen);

            UUID trackId = UUID.randomUUID();
            ExecutionTrackRecord track = createTrackRecord(trackId, exec, 0);

            when(executionRepository.findById(execId)).thenReturn(Optional.of(exec));
            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(execId)).thenReturn(List.of(track));
            when(trackRecordRepository.findDuePendingTracks(execId)).thenReturn(List.of(track));
            when(trackRecordRepository.claimTrackAddingWithFencing(trackId, execId, gen)).thenReturn(1);

            // Network read timeout during mutation
            when(musicDestination.addTrackToPlaylist(
                    eq(userId), eq("PL_dest_1"), any(AddTrackToDestinationPlaylistRequest.class)))
                    .thenThrow(new ResourceAccessException("Read timed out connecting to YouTube API"));

            when(trackRecordRepository.updateTrackStatusWithFencing(
                    eq(trackId), eq(execId), eq(gen), eq(ExecutionTrackStatus.UNCERTAIN), any(), anyString(), any()
            )).thenReturn(1);

            when(trackRecordRepository.findEarliestDeferredRetry(execId)).thenReturn(Optional.empty());
            when(executionRepository.saveAndFlush(any(MigrationExecution.class))).thenReturn(exec);

            MigrationExecutionSummary summary = service.executeClaimedTrackInsertion(execId, userId, gen);

            assertThat(summary).isNotNull();
            verify(trackRecordRepository).updateTrackStatusWithFencing(
                    eq(trackId), eq(execId), eq(gen), eq(ExecutionTrackStatus.UNCERTAIN), any(), anyString(), any()
            );
            verify(trackRecordRepository, never()).scheduleTrackRetryWithFencing(
                    any(), any(), anyLong(), any(), any(), any()
            );
        }

        @Test
        @DisplayName("Non-retryable 404 error marks track FAILED and skips remaining tracks")
        void nonRetryableErrorMarksFailed() {
            UUID execId = UUID.randomUUID();
            long gen = 1L;
            MigrationExecution exec = createRunningExecution(execId, gen);

            UUID trackId = UUID.randomUUID();
            ExecutionTrackRecord track = createTrackRecord(trackId, exec, 0);

            when(executionRepository.findById(execId)).thenReturn(Optional.of(exec));
            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(execId)).thenReturn(List.of(track));
            when(trackRecordRepository.findDuePendingTracks(execId)).thenReturn(List.of(track));
            when(trackRecordRepository.claimTrackAddingWithFencing(trackId, execId, gen)).thenReturn(1);

            ExternalProviderException notFoundEx = new ExternalProviderException("Video not found or deleted", 404);
            when(musicDestination.addTrackToPlaylist(
                    eq(userId), eq("PL_dest_1"), any(AddTrackToDestinationPlaylistRequest.class)))
                    .thenThrow(notFoundEx);

            when(trackRecordRepository.updateTrackStatusWithFencing(
                    eq(trackId), eq(execId), eq(gen), eq(ExecutionTrackStatus.FAILED), any(), anyString(), any()
            )).thenReturn(1);

            when(trackRecordRepository.findEarliestDeferredRetry(execId)).thenReturn(Optional.empty());
            when(executionRepository.saveAndFlush(any(MigrationExecution.class))).thenReturn(exec);

            MigrationExecutionSummary summary = service.executeClaimedTrackInsertion(execId, userId, gen);

            assertThat(summary).isNotNull();
            verify(trackRecordRepository).updateTrackStatusWithFencing(
                    eq(trackId), eq(execId), eq(gen), eq(ExecutionTrackStatus.FAILED), any(), anyString(), any()
            );
        }

        @Test
        @DisplayName("Successful track insertion updates status to ADDED with fencing")
        void successfulTrackInsertionUpdatesAdded() {
            UUID execId = UUID.randomUUID();
            long gen = 1L;
            MigrationExecution exec = createRunningExecution(execId, gen);

            UUID trackId = UUID.randomUUID();
            ExecutionTrackRecord track = createTrackRecord(trackId, exec, 0);

            when(executionRepository.findById(execId)).thenReturn(Optional.of(exec));
            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(execId)).thenReturn(List.of(track));
            when(trackRecordRepository.findDuePendingTracks(execId)).thenReturn(List.of(track));
            when(trackRecordRepository.claimTrackAddingWithFencing(trackId, execId, gen)).thenReturn(1);

            DestinationPlaylistItem confirmedItem = new DestinationPlaylistItem("item-123", "yt_0", "Title 0", 0);
            when(musicDestination.addTrackToPlaylist(
                    eq(userId), eq("PL_dest_1"), any(AddTrackToDestinationPlaylistRequest.class)))
                    .thenReturn(confirmedItem);

            when(trackRecordRepository.updateTrackStatusWithFencing(
                    eq(trackId), eq(execId), eq(gen), eq(ExecutionTrackStatus.ADDED), eq("item-123"), any(), any()
            )).thenReturn(1);

            when(trackRecordRepository.findEarliestDeferredRetry(execId)).thenReturn(Optional.empty());
            when(executionRepository.saveAndFlush(any(MigrationExecution.class))).thenReturn(exec);

            MigrationExecutionSummary summary = service.executeClaimedTrackInsertion(execId, userId, gen);

            assertThat(summary).isNotNull();
            verify(trackRecordRepository).updateTrackStatusWithFencing(
                    eq(trackId), eq(execId), eq(gen), eq(ExecutionTrackStatus.ADDED), eq("item-123"), any(), any()
            );
        }
    }
}
