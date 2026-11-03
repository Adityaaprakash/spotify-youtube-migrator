package com.spotifyyoutube.migrator.migration.integration;

import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.CreateDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItem;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.async.MigrationJobDispatcher;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.progress.application.MigrationProgressService;
import com.spotifyyoutube.migrator.migration.progress.domain.MigrationProgressSnapshot;
import com.spotifyyoutube.migrator.migration.review.domain.CandidateDecision;
import com.spotifyyoutube.migrator.migration.review.domain.MatchCandidate;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewTrackRepository;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PostgreSQL integration test for queued migration preparation, dispatching,
 * atomic claim, background track insertion, and idempotency (Phase 12G-E).
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("Phase 12G-E: Queued Migration Dispatcher Integration Test")
public class QueuedMigrationDispatcherIT {

    @Autowired
    private MigrationJobDispatcher dispatcher;

    @Autowired
    private MigrationProgressService progressService;

    @Autowired
    private MigrationExecutionRepository executionRepository;

    @Autowired
    private ExecutionTrackRecordRepository trackRecordRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReviewSessionRepository reviewSessionRepository;

    @Autowired
    private ReviewTrackRepository reviewTrackRepository;

    @MockBean
    private MusicDestination musicDestination;

    private User testUser;
    private ReviewSession testSession;

    @BeforeEach
    void setUp() {
        testUser = userRepository.save(new User("queued-it-" + UUID.randomUUID() + "@example.com"));

        testSession = new ReviewSession(testUser, "sp_pl_" + UUID.randomUUID(), "Queued Dispatcher PL", UUID.randomUUID());
        testSession.setStatus(ReviewSessionStatus.COMPLETED);
        testSession = reviewSessionRepository.save(testSession);

        // Add 2 approved review tracks
        ReviewTrack track0 = new ReviewTrack(testSession, 0, "sp_t0", "Track 0", "Artist", Duration.ofMinutes(3), "alb0");
        MatchCandidate c0 = new MatchCandidate("yt_t0", "Track 0 (YT)", "Channel", Duration.ofMinutes(3), 0.95);
        track0.addCandidate(c0);
        track0.applyDecision(CandidateDecision.APPROVE, c0.getId(), ReviewDecisionSource.AUTOMATIC, "High match");

        ReviewTrack track1 = new ReviewTrack(testSession, 1, "sp_t1", "Track 1", "Artist", Duration.ofMinutes(3), "alb1");
        MatchCandidate c1 = new MatchCandidate("yt_t1", "Track 1 (YT)", "Channel", Duration.ofMinutes(3), 0.90);
        track1.addCandidate(c1);
        track1.applyDecision(CandidateDecision.APPROVE, c1.getId(), ReviewDecisionSource.AUTOMATIC, "High match");

        reviewTrackRepository.saveAll(List.of(track0, track1));

        // Mock MusicDestination behavior
        when(musicDestination.getPlatform()).thenReturn(Platform.YOUTUBE);
        when(musicDestination.createPlaylist(eq(testUser.getId()), any(CreateDestinationPlaylistRequest.class)))
                .thenReturn(new DestinationPlaylist("yt_pl_created_123", "Queued Dispatcher PL", "https://music.youtube.com/playlist?list=yt_pl_created_123", Platform.YOUTUBE));

        when(musicDestination.insertTrack(eq(testUser.getId()), any(AddTrackToDestinationPlaylistRequest.class)))
                .thenReturn(new DestinationPlaylistItem("item_yt_" + UUID.randomUUID(), "yt_pl_created_123", "yt_t", 0));
    }

    @Test
    @DisplayName("Complete queued migration lifecycle: prepare -> dispatch -> atomic claim -> track insertion -> COMPLETED")
    void completeQueuedMigrationLifecycle() throws Exception {
        UUID userId = testUser.getId();
        UUID sessionId = testSession.getId();
        ApprovedMigrationExecutionRequest request = ApprovedMigrationExecutionRequest.of(sessionId, userId);

        // Stage 1 & 2: Dispatch approved migration
        MigrationExecutionSummary dispatchedSummary = dispatcher.dispatchMigration(request, userId);

        assertThat(dispatchedSummary).isNotNull();
        assertThat(dispatchedSummary.status()).isEqualTo(MigrationExecutionStatus.QUEUED);
        assertThat(dispatchedSummary.destinationPlaylistId()).isEqualTo("yt_pl_created_123");
        UUID executionId = dispatchedSummary.executionId();

        // Destination playlist creation happened exactly once during synchronous preparation
        verify(musicDestination, times(1)).createPlaylist(eq(userId), any());

        // Wait up to 5 seconds for background worker to claim and complete track insertion
        MigrationExecution completedExecution = null;
        for (int i = 0; i < 50; i++) {
            completedExecution = executionRepository.findById(executionId).orElse(null);
            if (completedExecution != null && completedExecution.getStatus() == MigrationExecutionStatus.COMPLETED) {
                break;
            }
            Thread.sleep(100);
        }

        assertThat(completedExecution).isNotNull();
        assertThat(completedExecution.getStatus()).isEqualTo(MigrationExecutionStatus.COMPLETED);
        assertThat(completedExecution.getSuccessfulInsertions()).isEqualTo(2);
        assertThat(completedExecution.getFailedInsertions()).isEqualTo(0);

        // Verify tracks were inserted via MusicDestination
        verify(musicDestination, times(2)).insertTrack(eq(userId), any());

        // Verify track records persisted in PostgreSQL are ADDED
        List<ExecutionTrackRecord> tracks = trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId);
        assertThat(tracks).hasSize(2);
        assertThat(tracks.get(0).getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);
        assertThat(tracks.get(1).getStatus()).isEqualTo(ExecutionTrackStatus.ADDED);

        // Verify progress service reflects 100% completion
        MigrationProgressSnapshot progress = progressService.getProgress(executionId, userId);
        assertThat(progress.totalTracks()).isEqualTo(2);
        assertThat(progress.processedTracks()).isEqualTo(2);
        assertThat(progress.remainingTracks()).isEqualTo(0);
        assertThat(progress.progressPercentage()).isEqualTo(100.0);

        // Test Idempotency: Submitting the same request again immediately returns COMPLETED
        MigrationExecutionSummary resubmittedSummary = dispatcher.dispatchMigration(request, userId);
        assertThat(resubmittedSummary.status()).isEqualTo(MigrationExecutionStatus.COMPLETED);
        assertThat(resubmittedSummary.executionId()).isEqualTo(executionId);

        // Destination playlist creation was NOT repeated
        verify(musicDestination, times(1)).createPlaylist(eq(userId), any());
    }
}
