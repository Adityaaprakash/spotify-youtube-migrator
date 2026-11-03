package com.spotifyyoutube.migrator.migration.progress.application;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.TrackStatusCount;
import com.spotifyyoutube.migrator.migration.progress.domain.MigrationProgressSnapshot;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DefaultMigrationProgressService} (Phase 12D).
 */
@DisplayName("Phase 12D: DefaultMigrationProgressService Test")
class DefaultMigrationProgressServiceTest {

    private MigrationExecutionRepository executionRepository;
    private ExecutionTrackRecordRepository trackRecordRepository;
    private DefaultMigrationProgressService progressService;

    private UUID userId;
    private User testUser;
    private UUID executionId;
    private MigrationExecution execution;
    private UUID sessionId;
    private ReviewSession reviewSession;

    @BeforeEach
    void setUp() {
        executionRepository = mock(MigrationExecutionRepository.class);
        trackRecordRepository = mock(ExecutionTrackRecordRepository.class);
        progressService = new DefaultMigrationProgressService(executionRepository, trackRecordRepository);

        userId = UUID.randomUUID();
        testUser = new User("migrator@example.com");
        testUser.setId(userId);

        sessionId = UUID.randomUUID();
        reviewSession = new ReviewSession(testUser, "sp_playlist_1", "Source Playlist", UUID.randomUUID());
        reviewSession.setId(sessionId);
        reviewSession.setStatus(ReviewSessionStatus.COMPLETED);

        ApprovedTrack t0 = new ApprovedTrack(0, "sp0", "Song 0", "Artist", "yt0", "Song 0 (Audio)", "Channel", Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 0);
        ApprovedTrack t1 = new ApprovedTrack(1, "sp1", "Song 1", "Artist", "yt1", "Song 1 (Audio)", "Channel", Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 1);
        ApprovedTrack t2 = new ApprovedTrack(2, "sp2", "Song 2", "Artist", "yt2", "Song 2 (Audio)", "Channel", Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 2);
        ApprovedTrack t3 = new ApprovedTrack(3, "sp3", "Song 3", "Artist", "yt3", "Song 3 (Audio)", "Channel", Duration.ofMinutes(3), ReviewDecisionSource.AUTOMATIC, 3);

        ApprovedMigrationContext context = new ApprovedMigrationContext(
                sessionId, UUID.randomUUID(), userId, "sp_playlist_1", "Source Playlist",
                "Target Playlist", "Description", false, List.of(t0, t1, t2, t3), 4, 4, 0, Instant.now()
        );

        executionId = UUID.randomUUID();
        execution = new MigrationExecution(testUser, reviewSession, context, "PL_target", "https://music.youtube.com/playlist?list=PL_target");
        execution.setId(executionId);
    }

    @Nested
    @DisplayName("Progress Retrieval Tests")
    class GetProgressTests {

        @Test
        @DisplayName("Returns accurate snapshot when execution exists and user is owner")
        void getProgress_Success() {
            execution.setStatus(MigrationExecutionStatus.RUNNING);
            when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));

            List<TrackStatusCount> mockCounts = List.of(
                    new TrackStatusCount(ExecutionTrackStatus.PENDING, 1L),
                    new TrackStatusCount(ExecutionTrackStatus.ADDING, 1L),
                    new TrackStatusCount(ExecutionTrackStatus.ADDED, 1L),
                    new TrackStatusCount(ExecutionTrackStatus.FAILED, 1L)
            );
            when(trackRecordRepository.countTracksByStatusForExecution(executionId)).thenReturn(mockCounts);

            MigrationProgressSnapshot snapshot = progressService.getProgress(executionId, userId);

            assertThat(snapshot).isNotNull();
            assertThat(snapshot.executionId()).isEqualTo(executionId);
            assertThat(snapshot.executionStatus()).isEqualTo(MigrationExecutionStatus.RUNNING);
            assertThat(snapshot.totalTracks()).isEqualTo(4);
            assertThat(snapshot.pendingTracks()).isEqualTo(1);
            assertThat(snapshot.addingTracks()).isEqualTo(1);
            assertThat(snapshot.addedTracks()).isEqualTo(1);
            assertThat(snapshot.failedTracks()).isEqualTo(1);
            assertThat(snapshot.uncertainTracks()).isEqualTo(0);
            assertThat(snapshot.skippedTracks()).isEqualTo(0);
            assertThat(snapshot.rollingBackTracks()).isEqualTo(0);
            assertThat(snapshot.rolledBackTracks()).isEqualTo(0);

            // Processed = added (1) + failed (1) = 2
            assertThat(snapshot.processedTracks()).isEqualTo(2);
            assertThat(snapshot.remainingTracks()).isEqualTo(2); // 4 - 2 = 2
            assertThat(snapshot.progressPercentage()).isEqualTo(50.0); // (2 / 4) * 100 = 50.0

            verify(trackRecordRepository).countTracksByStatusForExecution(executionId);
            // Verify read-only: no save/flush called
            verify(executionRepository, never()).save(any());
            verify(executionRepository, never()).saveAndFlush(any());
            verify(trackRecordRepository, never()).save(any());
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when execution does not exist")
        void getProgress_NotFound() {
            when(executionRepository.findById(executionId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> progressService.getProgress(executionId, userId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Migration execution not found");
        }

        @Test
        @DisplayName("Enforces anti-IDOR: throws ResourceNotFoundException when user is not owner")
        void getProgress_AntiIdor() {
            UUID otherUser = UUID.randomUUID();
            when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));

            assertThatThrownBy(() -> progressService.getProgress(executionId, otherUser))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Migration execution not found");

            verify(trackRecordRepository, never()).countTracksByStatusForExecution(any());
        }

        @Test
        @DisplayName("Missing statuses from repository query default to zero")
        void getProgress_MissingStatusesDefaultToZero() {
            execution.setStatus(MigrationExecutionStatus.COMPLETED);
            when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));

            // Only ADDED returned from database
            when(trackRecordRepository.countTracksByStatusForExecution(executionId))
                    .thenReturn(List.of(new TrackStatusCount(ExecutionTrackStatus.ADDED, 4L)));

            MigrationProgressSnapshot snapshot = progressService.getProgress(executionId, userId);

            assertThat(snapshot.addedTracks()).isEqualTo(4);
            assertThat(snapshot.pendingTracks()).isEqualTo(0);
            assertThat(snapshot.addingTracks()).isEqualTo(0);
            assertThat(snapshot.failedTracks()).isEqualTo(0);
            assertThat(snapshot.uncertainTracks()).isEqualTo(0);
            assertThat(snapshot.skippedTracks()).isEqualTo(0);
            assertThat(snapshot.rollingBackTracks()).isEqualTo(0);
            assertThat(snapshot.rolledBackTracks()).isEqualTo(0);
            assertThat(snapshot.processedTracks()).isEqualTo(4);
            assertThat(snapshot.remainingTracks()).isEqualTo(0);
            assertThat(snapshot.progressPercentage()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("Handles zero-track execution safely without division by zero")
        void getProgress_ZeroTracks() {
            MigrationExecution emptyExecution = new MigrationExecution();
            emptyExecution.setId(executionId);
            emptyExecution.setStatus(MigrationExecutionStatus.INITIALIZED);
            // User reflection / setup
            User user = new User("u@example.com");
            user.setId(userId);
            // We set user via reflection or field
            try {
                var field = MigrationExecution.class.getDeclaredField("user");
                field.setAccessible(true);
                field.set(emptyExecution, user);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }

            when(executionRepository.findById(executionId)).thenReturn(Optional.of(emptyExecution));
            when(trackRecordRepository.countTracksByStatusForExecution(executionId)).thenReturn(List.of());

            MigrationProgressSnapshot snapshot = progressService.getProgress(executionId, userId);

            assertThat(snapshot.totalTracks()).isEqualTo(0);
            assertThat(snapshot.processedTracks()).isEqualTo(0);
            assertThat(snapshot.remainingTracks()).isEqualTo(0);
            assertThat(snapshot.progressPercentage()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Progress By Review Session Tests")
    class GetProgressByReviewSessionTests {

        @Test
        @DisplayName("Returns snapshot when review session execution exists and belongs to user")
        void getProgressByReviewSession_Success() {
            execution.setStatus(MigrationExecutionStatus.QUEUED);
            when(executionRepository.findByReviewSessionId(sessionId)).thenReturn(Optional.of(execution));
            when(trackRecordRepository.countTracksByStatusForExecution(executionId))
                    .thenReturn(List.of(new TrackStatusCount(ExecutionTrackStatus.PENDING, 4L)));

            MigrationProgressSnapshot snapshot = progressService.getProgressByReviewSession(sessionId, userId);

            assertThat(snapshot).isNotNull();
            assertThat(snapshot.executionId()).isEqualTo(executionId);
            assertThat(snapshot.executionStatus()).isEqualTo(MigrationExecutionStatus.QUEUED);
            assertThat(snapshot.pendingTracks()).isEqualTo(4);
            assertThat(snapshot.processedTracks()).isEqualTo(0);
            assertThat(snapshot.progressPercentage()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when review session execution is missing")
        void getProgressByReviewSession_NotFound() {
            when(executionRepository.findByReviewSessionId(sessionId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> progressService.getProgressByReviewSession(sessionId, userId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Migration execution not found for review session");
        }

        @Test
        @DisplayName("Enforces anti-IDOR: throws ResourceNotFoundException when caller is not owner")
        void getProgressByReviewSession_AntiIdor() {
            UUID otherUser = UUID.randomUUID();
            when(executionRepository.findByReviewSessionId(sessionId)).thenReturn(Optional.of(execution));

            assertThatThrownBy(() -> progressService.getProgressByReviewSession(sessionId, otherUser))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Migration execution not found for review session");
        }
    }

    @Nested
    @DisplayName("Execution Status Support & Validation")
    class StatusSupportTests {

        @ParameterizedTest
        @EnumSource(MigrationExecutionStatus.class)
        @DisplayName("Supports all MigrationExecutionStatus enum values")
        void supportsAllExecutionStatuses(MigrationExecutionStatus status) {
            execution.setStatus(status);
            when(executionRepository.findById(executionId)).thenReturn(Optional.of(execution));
            when(trackRecordRepository.countTracksByStatusForExecution(executionId)).thenReturn(List.of());

            MigrationProgressSnapshot snapshot = progressService.getProgress(executionId, userId);

            assertThat(snapshot.executionStatus()).isEqualTo(status);
        }

        @Test
        @DisplayName("Null arguments throw NullPointerException")
        void nullArguments() {
            assertThatThrownBy(() -> progressService.getProgress(null, userId))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("executionId must not be null");

            assertThatThrownBy(() -> progressService.getProgress(executionId, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("authenticatedUserId must not be null");

            assertThatThrownBy(() -> progressService.getProgressByReviewSession(null, userId))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("reviewSessionId must not be null");

            assertThatThrownBy(() -> progressService.getProgressByReviewSession(sessionId, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("authenticatedUserId must not be null");
        }
    }
}
