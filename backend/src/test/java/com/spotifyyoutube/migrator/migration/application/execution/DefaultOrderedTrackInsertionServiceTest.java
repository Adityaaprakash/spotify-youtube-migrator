package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItem;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.InsertTracksExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.PlaylistTrackInsertionSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.TrackInsertionOutcome;
import com.spotifyyoutube.migrator.migration.application.execution.contract.TrackInsertionStatus;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import com.spotifyyoutube.migrator.youtube.application.YouTubePlaylistService;
import com.spotifyyoutube.migrator.youtube.dto.YouTubePlaylistDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 11D: Default Ordered Track Insertion Service Test")
class DefaultOrderedTrackInsertionServiceTest {

    @Mock
    private ApprovedMigrationExecutionService executionService;

    @Mock
    private MusicDestination musicDestination;

    @Mock
    private YouTubePlaylistService youTubePlaylistService;

    @InjectMocks
    private DefaultOrderedTrackInsertionService insertionService;

    private UUID userId;
    private UUID sessionId;
    private UUID planId;
    private String playlistId;
    private InsertTracksExecutionRequest request;
    private ApprovedMigrationContext validContext;
    private ApprovedTrack track0;
    private ApprovedTrack track1;
    private ApprovedTrack track2;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        planId = UUID.randomUUID();
        playlistId = "PL_valid_dest_12345";
        request = InsertTracksExecutionRequest.of(sessionId, playlistId, userId);

        track0 = new ApprovedTrack(
                0, "sp-0", "Song 0", "Artist 0", "yt-0", "Title 0", "Channel 0",
                Duration.ofSeconds(200), ReviewDecisionSource.AUTOMATIC, 0
        );
        track1 = new ApprovedTrack(
                1, "sp-1", "Song 1", "Artist 1", "yt-1", "Title 1", "Channel 1",
                Duration.ofSeconds(180), ReviewDecisionSource.USER_SELECTED, 1
        );
        track2 = new ApprovedTrack(
                2, "sp-2", "Song 2", "Artist 2", "yt-2", "Title 2", "Channel 2",
                Duration.ofSeconds(210), ReviewDecisionSource.AUTOMATIC, 2
        );

        validContext = new ApprovedMigrationContext(
                sessionId, planId, userId, "sp-pl-1", "Source Playlist",
                "Migrated - Source Playlist", "Migrated description", true,
                List.of(track0, track1, track2), 3, 3, 0, Instant.now()
        );
    }

    private void mockPlaylistExists() {
        when(youTubePlaylistService.getPlaylist(userId, playlistId))
                .thenReturn(new YouTubePlaylistDto(playlistId, "Migrated Playlist", "Description", "private", 0L, null));
    }

    @Nested
    @DisplayName("Pre-Mutation Validation & Authorization Tests")
    class ValidationTests {

        @Test
        @DisplayName("Rejects null execution request")
        void rejectsNullRequest() {
            assertThatThrownBy(() -> insertionService.insertApprovedTracks(null, userId))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("request must not be null");
        }

        @Test
        @DisplayName("Rejects null authenticated userId")
        void rejectsNullUserId() {
            assertThatThrownBy(() -> insertionService.insertApprovedTracks(request, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("authenticatedUserId must not be null");
        }

        @Test
        @DisplayName("Anti-impersonation: rejects caller-supplied userId differing from authenticated userId")
        void rejectsImpersonation() {
            UUID otherUserId = UUID.randomUUID();
            InsertTracksExecutionRequest maliciousReq = new InsertTracksExecutionRequest(sessionId, playlistId, otherUserId);

            assertThatThrownBy(() -> insertionService.insertApprovedTracks(maliciousReq, userId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match authenticated user ID");

            verifyNoInteractions(musicDestination);
            verifyNoInteractions(youTubePlaylistService);
        }

        @Test
        @DisplayName("Rejects when destination playlist is not accessible or not found")
        void destinationPlaylistNotFound() {
            when(executionService.prepareExecution(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(validContext);
            when(musicDestination.getPlatform()).thenReturn(Platform.YOUTUBE);
            when(youTubePlaylistService.getPlaylist(userId, playlistId))
                    .thenThrow(new ResourceNotFoundException("Playlist not found"));

            assertThatThrownBy(() -> insertionService.insertApprovedTracks(request, userId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Destination playlist not found or inaccessible");

            verify(musicDestination, never()).addTrackToPlaylist(any(), any(), any());
        }

        @Test
        @DisplayName("Rejects non-YouTube destination platform")
        void rejectsNonYouTubePlatform() {
            when(executionService.prepareExecution(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(validContext);
            when(musicDestination.getPlatform()).thenReturn(Platform.SPOTIFY);

            assertThatThrownBy(() -> insertionService.insertApprovedTracks(request, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("Only YOUTUBE destination platform is supported");

            verifyNoInteractions(youTubePlaylistService);
            verify(musicDestination, never()).addTrackToPlaylist(any(), any(), any());
        }

        @Test
        @DisplayName("Rejects when approved migration context contains zero approved tracks")
        void rejectsZeroApprovedTracks() {
            ApprovedMigrationContext emptyContext = new ApprovedMigrationContext(
                    sessionId, planId, userId, "sp-pl-1", "Source Playlist",
                    "Migrated - Source Playlist", "Migrated description", true,
                    List.of(), 3, 0, 3, Instant.now()
            );

            when(executionService.prepareExecution(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(emptyContext);

            assertThatThrownBy(() -> insertionService.insertApprovedTracks(request, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("no approved tracks available for insertion");

            verifyNoInteractions(youTubePlaylistService);
            verify(musicDestination, never()).addTrackToPlaylist(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Ordered Sequential Insertion Tests")
    class OrderedInsertionTests {

        @Test
        @DisplayName("Successfully inserts tracks sequentially in ascending targetPosition order with InOrder verification")
        void insertsTracksInCanonicalOrder() {
            when(executionService.prepareExecution(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(validContext);
            when(musicDestination.getPlatform()).thenReturn(Platform.YOUTUBE);
            mockPlaylistExists();

            when(musicDestination.addTrackToPlaylist(eq(userId), eq(playlistId), any(AddTrackToDestinationPlaylistRequest.class)))
                    .thenReturn(new DestinationPlaylistItem("item-0", playlistId, "yt-0", 0))
                    .thenReturn(new DestinationPlaylistItem("item-1", playlistId, "yt-1", 1))
                    .thenReturn(new DestinationPlaylistItem("item-2", playlistId, "yt-2", 2));

            PlaylistTrackInsertionSummary summary = insertionService.insertApprovedTracks(request, userId);

            assertThat(summary.fullySuccessful()).isTrue();
            assertThat(summary.getExecutionStatus()).isEqualTo("COMPLETED");
            assertThat(summary.totalApprovedTracks()).isEqualTo(3);
            assertThat(summary.successfulInsertions()).isEqualTo(3);
            assertThat(summary.failedInsertions()).isEqualTo(0);
            assertThat(summary.uncertainInsertions()).isEqualTo(0);
            assertThat(summary.skippedInsertions()).isEqualTo(0);

            // InOrder verification of provider mutations
            InOrder inOrder = inOrder(musicDestination);
            inOrder.verify(musicDestination).addTrackToPlaylist(eq(userId), eq(playlistId), eq(new AddTrackToDestinationPlaylistRequest("yt-0", 0)));
            inOrder.verify(musicDestination).addTrackToPlaylist(eq(userId), eq(playlistId), eq(new AddTrackToDestinationPlaylistRequest("yt-1", 1)));
            inOrder.verify(musicDestination).addTrackToPlaylist(eq(userId), eq(playlistId), eq(new AddTrackToDestinationPlaylistRequest("yt-2", 2)));
            inOrder.verifyNoMoreInteractions();

            // Verify outcomes list structure
            List<TrackInsertionOutcome> outcomes = summary.trackOutcomes();
            assertThat(outcomes).hasSize(3);
            assertThat(outcomes.get(0).status()).isEqualTo(TrackInsertionStatus.SUCCESS);
            assertThat(outcomes.get(0).destinationPlaylistItemId()).isEqualTo("item-0");
            assertThat(outcomes.get(1).status()).isEqualTo(TrackInsertionStatus.SUCCESS);
            assertThat(outcomes.get(1).destinationPlaylistItemId()).isEqualTo("item-1");
            assertThat(outcomes.get(2).status()).isEqualTo(TrackInsertionStatus.SUCCESS);
            assertThat(outcomes.get(2).destinationPlaylistItemId()).isEqualTo("item-2");
        }

        @Test
        @DisplayName("Preserves targetPosition ordering even if context list order is unordered")
        void preservesOrderRegardlessOfContextListOrder() {
            // Unordered list in context: track 2, track 0, track 1
            ApprovedMigrationContext scrambledContext = new ApprovedMigrationContext(
                    sessionId, planId, userId, "sp-pl-1", "Source Playlist",
                    "Migrated - Source Playlist", "Migrated description", true,
                    List.of(track2, track0, track1), 3, 3, 0, Instant.now()
            );

            when(executionService.prepareExecution(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(scrambledContext);
            when(musicDestination.getPlatform()).thenReturn(Platform.YOUTUBE);
            mockPlaylistExists();

            when(musicDestination.addTrackToPlaylist(eq(userId), eq(playlistId), any(AddTrackToDestinationPlaylistRequest.class)))
                    .thenReturn(new DestinationPlaylistItem("item-0", playlistId, "yt-0", 0))
                    .thenReturn(new DestinationPlaylistItem("item-1", playlistId, "yt-1", 1))
                    .thenReturn(new DestinationPlaylistItem("item-2", playlistId, "yt-2", 2));

            PlaylistTrackInsertionSummary summary = insertionService.insertApprovedTracks(request, userId);

            // Must still insert in ascending targetPosition: track0 (0), track1 (1), track2 (2)
            InOrder inOrder = inOrder(musicDestination);
            inOrder.verify(musicDestination).addTrackToPlaylist(eq(userId), eq(playlistId), eq(new AddTrackToDestinationPlaylistRequest("yt-0", 0)));
            inOrder.verify(musicDestination).addTrackToPlaylist(eq(userId), eq(playlistId), eq(new AddTrackToDestinationPlaylistRequest("yt-1", 1)));
            inOrder.verify(musicDestination).addTrackToPlaylist(eq(userId), eq(playlistId), eq(new AddTrackToDestinationPlaylistRequest("yt-2", 2)));

            assertThat(summary.trackOutcomes().get(0).targetPosition()).isEqualTo(0);
            assertThat(summary.trackOutcomes().get(1).targetPosition()).isEqualTo(1);
            assertThat(summary.trackOutcomes().get(2).targetPosition()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Partial Failure & Stopping Policy Tests")
    class FailurePolicyTests {

        @Test
        @DisplayName("Confirmed provider rejection stops subsequent insertions and marks them SKIPPED")
        void confirmedFailureStopsSubsequentTracks() {
            when(executionService.prepareExecution(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(validContext);
            when(musicDestination.getPlatform()).thenReturn(Platform.YOUTUBE);
            mockPlaylistExists();

            // Track 0 succeeds
            when(musicDestination.addTrackToPlaylist(eq(userId), eq(playlistId), eq(new AddTrackToDestinationPlaylistRequest("yt-0", 0))))
                    .thenReturn(new DestinationPlaylistItem("item-0", playlistId, "yt-0", 0));

            // Track 1 fails with provider rejection
            when(musicDestination.addTrackToPlaylist(eq(userId), eq(playlistId), eq(new AddTrackToDestinationPlaylistRequest("yt-1", 1))))
                    .thenThrow(new ExternalProviderException("Video yt-1 not found in YouTube catalog"));

            PlaylistTrackInsertionSummary summary = insertionService.insertApprovedTracks(request, userId);

            // Track 2 must never be attempted
            verify(musicDestination, times(2)).addTrackToPlaylist(any(), any(), any());
            verify(musicDestination, never()).addTrackToPlaylist(eq(userId), eq(playlistId), eq(new AddTrackToDestinationPlaylistRequest("yt-2", 2)));

            assertThat(summary.fullySuccessful()).isFalse();
            assertThat(summary.getExecutionStatus()).isEqualTo("PARTIALLY_FAILED");
            assertThat(summary.successfulInsertions()).isEqualTo(1);
            assertThat(summary.failedInsertions()).isEqualTo(1);
            assertThat(summary.uncertainInsertions()).isEqualTo(0);
            assertThat(summary.skippedInsertions()).isEqualTo(1);

            List<TrackInsertionOutcome> outcomes = summary.trackOutcomes();
            assertThat(outcomes.get(0).status()).isEqualTo(TrackInsertionStatus.SUCCESS);
            assertThat(outcomes.get(1).status()).isEqualTo(TrackInsertionStatus.FAILED);
            assertThat(outcomes.get(1).message()).contains("Video yt-1 not found in YouTube catalog");
            assertThat(outcomes.get(2).status()).isEqualTo(TrackInsertionStatus.SKIPPED);
            assertThat(outcomes.get(2).message()).contains("Skipped due to prior insertion failure at target position 1");
        }

        @Test
        @DisplayName("Ambiguous timeout marks current track UNCERTAIN and stops subsequent insertions as SKIPPED")
        void ambiguousTimeoutStopsSubsequentTracks() {
            when(executionService.prepareExecution(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(validContext);
            when(musicDestination.getPlatform()).thenReturn(Platform.YOUTUBE);
            mockPlaylistExists();

            // Track 0 succeeds
            when(musicDestination.addTrackToPlaylist(eq(userId), eq(playlistId), eq(new AddTrackToDestinationPlaylistRequest("yt-0", 0))))
                    .thenReturn(new DestinationPlaylistItem("item-0", playlistId, "yt-0", 0));

            // Track 1 times out
            when(musicDestination.addTrackToPlaylist(eq(userId), eq(playlistId), eq(new AddTrackToDestinationPlaylistRequest("yt-1", 1))))
                    .thenThrow(new ResourceAccessException("I/O error on POST request: Read timed out"));

            PlaylistTrackInsertionSummary summary = insertionService.insertApprovedTracks(request, userId);

            // Track 2 must never be attempted
            verify(musicDestination, times(2)).addTrackToPlaylist(any(), any(), any());

            assertThat(summary.fullySuccessful()).isFalse();
            assertThat(summary.getExecutionStatus()).isEqualTo("STOPPED_ON_UNCERTAIN_OUTCOME");
            assertThat(summary.successfulInsertions()).isEqualTo(1);
            assertThat(summary.failedInsertions()).isEqualTo(0);
            assertThat(summary.uncertainInsertions()).isEqualTo(1);
            assertThat(summary.skippedInsertions()).isEqualTo(1);

            List<TrackInsertionOutcome> outcomes = summary.trackOutcomes();
            assertThat(outcomes.get(0).status()).isEqualTo(TrackInsertionStatus.SUCCESS);
            assertThat(outcomes.get(1).status()).isEqualTo(TrackInsertionStatus.UNCERTAIN);
            assertThat(outcomes.get(1).message()).contains("Remote outcome uncertain due to network timeout or connection reset");
            assertThat(outcomes.get(2).status()).isEqualTo(TrackInsertionStatus.SKIPPED);
            assertThat(outcomes.get(2).message()).contains("Skipped due to prior uncertain outcome at target position 1");
        }

        @Test
        @DisplayName("First track failure results in FAILED execution status and all remaining SKIPPED")
        void firstTrackFailureMarksExecutionFailed() {
            when(executionService.prepareExecution(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(validContext);
            when(musicDestination.getPlatform()).thenReturn(Platform.YOUTUBE);
            mockPlaylistExists();

            when(musicDestination.addTrackToPlaylist(eq(userId), eq(playlistId), eq(new AddTrackToDestinationPlaylistRequest("yt-0", 0))))
                    .thenThrow(new ExternalProviderException("YouTube video private or deleted"));

            PlaylistTrackInsertionSummary summary = insertionService.insertApprovedTracks(request, userId);

            verify(musicDestination, times(1)).addTrackToPlaylist(any(), any(), any());

            assertThat(summary.fullySuccessful()).isFalse();
            assertThat(summary.getExecutionStatus()).isEqualTo("FAILED");
            assertThat(summary.successfulInsertions()).isEqualTo(0);
            assertThat(summary.failedInsertions()).isEqualTo(1);
            assertThat(summary.uncertainInsertions()).isEqualTo(0);
            assertThat(summary.skippedInsertions()).isEqualTo(2);
        }

        @Test
        @DisplayName("Sensitive Bearer and OAuth tokens in provider error message are scrubbed")
        void sanitizesSensitiveTokens() {
            when(executionService.prepareExecution(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(validContext);
            when(musicDestination.getPlatform()).thenReturn(Platform.YOUTUBE);
            mockPlaylistExists();

            when(musicDestination.addTrackToPlaylist(eq(userId), eq(playlistId), any()))
                    .thenThrow(new ExternalProviderException("Failed call with Bearer ya29.a0AfH6SMDI83921839218391283921839128391283 auth header"));

            PlaylistTrackInsertionSummary summary = insertionService.insertApprovedTracks(request, userId);

            assertThat(summary.trackOutcomes().get(0).message())
                    .doesNotContain("ya29.")
                    .contains("[REDACTED_TOKEN]");
        }
    }
}
