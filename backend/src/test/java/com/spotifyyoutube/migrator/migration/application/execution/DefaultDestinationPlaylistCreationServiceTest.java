package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.CreateDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.DestinationPlaylistCreationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationError;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionValidationException;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ValidationFailureCode;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 11C: Destination Playlist Creation Service Test")
class DefaultDestinationPlaylistCreationServiceTest {

    @Mock
    private ApprovedMigrationExecutionService executionService;

    @Mock
    private MusicDestination musicDestination;

    @InjectMocks
    private DefaultDestinationPlaylistCreationService playlistCreationService;

    private UUID userId;
    private UUID sessionId;
    private UUID planId;
    private ApprovedMigrationExecutionRequest request;
    private ApprovedMigrationContext validContext;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        planId = UUID.randomUUID();
        request = ApprovedMigrationExecutionRequest.of(sessionId, userId, "Custom Title");

        ApprovedTrack track0 = new ApprovedTrack(
                0, "sp-0", "Song 0", "Artist 0", "yt-0", "Title 0", "Channel 0",
                Duration.ofSeconds(200), ReviewDecisionSource.AUTOMATIC, 0
        );
        ApprovedTrack track1 = new ApprovedTrack(
                1, "sp-1", "Song 1", "Artist 1", "yt-1", "Title 1", "Channel 1",
                Duration.ofSeconds(180), ReviewDecisionSource.USER_SELECTED, 1
        );

        validContext = new ApprovedMigrationContext(
                sessionId, planId, userId, "spotify-pl-1", "Source Playlist",
                "Migrated - Source Playlist", "Migrated description", true,
                List.of(track0, track1), 2, 2, 0, Instant.now()
        );
    }

    @Nested
    @DisplayName("Successful Playlist Creation Tests")
    class SuccessTests {

        @Test
        @DisplayName("Successfully creates destination playlist with validated metadata and zero track insertions")
        void createDestinationPlaylist_Success() {
            when(executionService.prepareExecution(request, userId)).thenReturn(validContext);
            when(musicDestination.getPlatform()).thenReturn(Platform.YOUTUBE);

            DestinationPlaylist createdPlaylist = new DestinationPlaylist(
                    "PL_created_999",
                    "Migrated - Source Playlist",
                    "Migrated description",
                    "private",
                    "https://www.youtube.com/playlist?list=PL_created_999",
                    Platform.YOUTUBE
            );
            when(musicDestination.createPlaylist(eq(userId), any(CreateDestinationPlaylistRequest.class)))
                    .thenReturn(createdPlaylist);

            DestinationPlaylistCreationResult result = playlistCreationService.createDestinationPlaylist(request, userId);

            assertThat(result).isNotNull();
            assertThat(result.reviewSessionId()).isEqualTo(sessionId);
            assertThat(result.getPlanId()).contains(planId);
            assertThat(result.userId()).isEqualTo(userId);
            assertThat(result.destinationPlatform()).isEqualTo(Platform.YOUTUBE);
            assertThat(result.destinationPlaylistId()).isEqualTo("PL_created_999");
            assertThat(result.getDestinationPlaylistUrl()).contains("https://www.youtube.com/playlist?list=PL_created_999");
            assertThat(result.playlistTitle()).isEqualTo("Migrated - Source Playlist");
            assertThat(result.getPlaylistDescription()).contains("Migrated description");
            assertThat(result.isPrivate()).isTrue();
            assertThat(result.approvedTrackCount()).isEqualTo(2);
            assertThat(result.createdAt()).isNotNull();

            // Verify provider invocation parameters
            ArgumentCaptor<CreateDestinationPlaylistRequest> captor = ArgumentCaptor.forClass(CreateDestinationPlaylistRequest.class);
            verify(musicDestination, times(1)).createPlaylist(eq(userId), captor.capture());
            CreateDestinationPlaylistRequest captured = captor.getValue();
            assertThat(captured.title()).isEqualTo("Migrated - Source Playlist");
            assertThat(captured.description()).isEqualTo("Migrated description");
            assertThat(captured.isPrivate()).isTrue();

            // STRICT MUTATION BOUNDARY: Zero track insertions
            verify(musicDestination, never()).addTrackToPlaylist(any(), any(), any());
        }

        @Test
        @DisplayName("Convenience overload by sessionId delegates and creates playlist successfully")
        void createDestinationPlaylist_ConvenienceMethod_Success() {
            when(executionService.prepareExecution(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(validContext);
            when(musicDestination.getPlatform()).thenReturn(Platform.YOUTUBE);

            DestinationPlaylist createdPlaylist = new DestinationPlaylist(
                    "PL_conv_123",
                    "Migrated - Source Playlist",
                    "Desc",
                    "private",
                    "https://www.youtube.com/playlist?list=PL_conv_123",
                    Platform.YOUTUBE
            );
            when(musicDestination.createPlaylist(eq(userId), any(CreateDestinationPlaylistRequest.class)))
                    .thenReturn(createdPlaylist);

            DestinationPlaylistCreationResult result = playlistCreationService.createDestinationPlaylist(sessionId, userId);

            assertThat(result.destinationPlaylistId()).isEqualTo("PL_conv_123");
            verify(musicDestination, times(1)).createPlaylist(eq(userId), any());
            verify(musicDestination, never()).addTrackToPlaylist(any(), any(), any());
        }

        @Test
        @DisplayName("Falls back to standard URL construction if provider returns null url")
        void createDestinationPlaylist_FallbackUrl() {
            when(executionService.prepareExecution(request, userId)).thenReturn(validContext);
            when(musicDestination.getPlatform()).thenReturn(Platform.YOUTUBE);

            DestinationPlaylist createdPlaylist = new DestinationPlaylist(
                    "PL_nourl_456",
                    "Migrated - Source Playlist",
                    null,
                    "private",
                    null,
                    Platform.YOUTUBE
            );
            when(musicDestination.createPlaylist(eq(userId), any(CreateDestinationPlaylistRequest.class)))
                    .thenReturn(createdPlaylist);

            DestinationPlaylistCreationResult result = playlistCreationService.createDestinationPlaylist(request, userId);

            assertThat(result.destinationPlaylistId()).isEqualTo("PL_nourl_456");
            assertThat(result.getDestinationPlaylistUrl()).contains("https://www.youtube.com/playlist?list=PL_nourl_456");
            verify(musicDestination, never()).addTrackToPlaylist(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Pre-Mutation Validation & Guard Tests")
    class ValidationGuardTests {

        @Test
        @DisplayName("Rejects null request before destination mutation")
        void rejectsNullRequest() {
            assertThatThrownBy(() -> playlistCreationService.createDestinationPlaylist(null, userId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Execution request must not be null");

            verifyNoInteractions(musicDestination);
        }

        @Test
        @DisplayName("Rejects null authenticatedUserId before destination mutation")
        void rejectsNullUserId() {
            assertThatThrownBy(() -> playlistCreationService.createDestinationPlaylist(request, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Authenticated user ID must not be null");

            verifyNoInteractions(musicDestination);
        }

        @Test
        @DisplayName("Anti-impersonation: Rejects request when request.userId does not match authenticated user")
        void rejectsImpersonation() {
            UUID otherUser = UUID.randomUUID();
            ApprovedMigrationExecutionRequest impRequest = ApprovedMigrationExecutionRequest.of(sessionId, otherUser);

            assertThatThrownBy(() -> playlistCreationService.createDestinationPlaylist(impRequest, userId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match authenticated user");

            verifyNoInteractions(musicDestination);
        }

        @Test
        @DisplayName("Empty approved track selection throws InvalidStateException without destination mutation")
        void emptyApprovedSelectionFails() {
            ApprovedMigrationContext emptyContext = new ApprovedMigrationContext(
                    sessionId, planId, userId, "spotify-pl-1", "Source Playlist",
                    "Target Title", "Target Desc", true,
                    List.of(), 1, 0, 1, Instant.now()
            );
            when(executionService.prepareExecution(request, userId)).thenReturn(emptyContext);

            assertThatThrownBy(() -> playlistCreationService.createDestinationPlaylist(request, userId))
                    .isInstanceOf(InvalidStateException.class)
                    .hasMessageContaining("approved track selection is empty");

            verifyNoInteractions(musicDestination);
        }

        @Test
        @DisplayName("Propagates ResourceNotFoundException when review session does not exist or fails anti-IDOR")
        void sessionNotFoundOrIdorFails() {
            when(executionService.prepareExecution(request, userId))
                    .thenThrow(new ResourceNotFoundException("Review session not found with id: " + sessionId));

            assertThatThrownBy(() -> playlistCreationService.createDestinationPlaylist(request, userId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Review session not found");

            verifyNoInteractions(musicDestination);
        }

        @Test
        @DisplayName("Propagates MigrationExecutionValidationException when session review is incomplete")
        void incompleteReviewFails() {
            ExecutionValidationResult errorResult = ExecutionValidationResult.invalid(
                    ValidationFailureCode.INCOMPLETE_REVIEW, "Review session is still in progress"
            );
            when(executionService.prepareExecution(request, userId))
                    .thenThrow(new MigrationExecutionValidationException(errorResult));

            assertThatThrownBy(() -> playlistCreationService.createDestinationPlaylist(request, userId))
                    .isInstanceOf(MigrationExecutionValidationException.class);

            verifyNoInteractions(musicDestination);
        }

        @Test
        @DisplayName("Rejects playlist title exceeding maximum length of 150 characters before destination mutation")
        void excessiveTitleLengthFails() {
            String longTitle = "A".repeat(151);
            ApprovedTrack track = new ApprovedTrack(
                    0, "sp-0", "Song 0", "Artist 0", "yt-0", "Title 0", "Channel 0",
                    Duration.ofSeconds(200), ReviewDecisionSource.AUTOMATIC, 0
            );
            ApprovedMigrationContext longTitleContext = new ApprovedMigrationContext(
                    sessionId, planId, userId, "spotify-pl-1", "Source Playlist",
                    longTitle, "Description", true,
                    List.of(track), 1, 1, 0, Instant.now()
            );
            when(executionService.prepareExecution(request, userId)).thenReturn(longTitleContext);

            assertThatThrownBy(() -> playlistCreationService.createDestinationPlaylist(request, userId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds maximum length of 150 characters");

            verifyNoInteractions(musicDestination);
        }

        @Test
        @DisplayName("Rejects playlist description exceeding maximum length of 5000 characters before destination mutation")
        void excessiveDescriptionLengthFails() {
            String longDesc = "D".repeat(5001);
            ApprovedTrack track = new ApprovedTrack(
                    0, "sp-0", "Song 0", "Artist 0", "yt-0", "Title 0", "Channel 0",
                    Duration.ofSeconds(200), ReviewDecisionSource.AUTOMATIC, 0
            );
            ApprovedMigrationContext longDescContext = new ApprovedMigrationContext(
                    sessionId, planId, userId, "spotify-pl-1", "Source Playlist",
                    "Valid Title", longDesc, true,
                    List.of(track), 1, 1, 0, Instant.now()
            );
            when(executionService.prepareExecution(request, userId)).thenReturn(longDescContext);

            assertThatThrownBy(() -> playlistCreationService.createDestinationPlaylist(request, userId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds maximum length of 5000 characters");

            verifyNoInteractions(musicDestination);
        }
    }

    @Nested
    @DisplayName("Provider Error Handling & Failure Boundary Tests")
    class ProviderErrorTests {

        @Test
        @DisplayName("Propagates ExternalProviderException and redacts tokens on provider rejection")
        void providerRejectionHandled() {
            when(executionService.prepareExecution(request, userId)).thenReturn(validContext);
            when(musicDestination.createPlaylist(eq(userId), any()))
                    .thenThrow(new ExternalProviderException("YouTube quota exceeded with bearer ya29.secret_token", 403));

            assertThatThrownBy(() -> playlistCreationService.createDestinationPlaylist(request, userId))
                    .isInstanceOf(ExternalProviderException.class)
                    .hasMessageContaining("YouTube quota exceeded");

            verify(musicDestination, never()).addTrackToPlaylist(any(), any(), any());
        }

        @Test
        @DisplayName("Propagates ResourceAccessException on timeout and acknowledges uncertain outcome")
        void providerTimeoutHandled() {
            when(executionService.prepareExecution(request, userId)).thenReturn(validContext);
            when(musicDestination.createPlaylist(eq(userId), any()))
                    .thenThrow(new ResourceAccessException("Connection timed out to www.googleapis.com"));

            assertThatThrownBy(() -> playlistCreationService.createDestinationPlaylist(request, userId))
                    .isInstanceOf(ResourceAccessException.class)
                    .hasMessageContaining("Connection timed out");

            verify(musicDestination, never()).addTrackToPlaylist(any(), any(), any());
        }

        @Test
        @DisplayName("Throws ExternalProviderException when provider returns null playlist")
        void providerReturnsNullPlaylist() {
            when(executionService.prepareExecution(request, userId)).thenReturn(validContext);
            when(musicDestination.createPlaylist(eq(userId), any())).thenReturn(null);

            assertThatThrownBy(() -> playlistCreationService.createDestinationPlaylist(request, userId))
                    .isInstanceOf(ExternalProviderException.class)
                    .hasMessageContaining("empty or missing playlist ID");

            verify(musicDestination, never()).addTrackToPlaylist(any(), any(), any());
        }

        @Test
        @DisplayName("Throws ExternalProviderException when provider returns playlist with blank ID")
        void providerReturnsBlankPlaylistId() {
            when(executionService.prepareExecution(request, userId)).thenReturn(validContext);
            DestinationPlaylist blankIdPlaylist = new DestinationPlaylist(
                    "   ", "Title", "Desc", "private", null, Platform.YOUTUBE
            );
            when(musicDestination.createPlaylist(eq(userId), any())).thenReturn(blankIdPlaylist);

            assertThatThrownBy(() -> playlistCreationService.createDestinationPlaylist(request, userId))
                    .isInstanceOf(ExternalProviderException.class)
                    .hasMessageContaining("empty or missing playlist ID");

            verify(musicDestination, never()).addTrackToPlaylist(any(), any(), any());
        }

        @Test
        @DisplayName("Propagates RestClientException when provider client error occurs")
        void restClientExceptionHandled() {
            when(executionService.prepareExecution(request, userId)).thenReturn(validContext);
            when(musicDestination.createPlaylist(eq(userId), any()))
                    .thenThrow(new RestClientException("502 Bad Gateway from Google API"));

            assertThatThrownBy(() -> playlistCreationService.createDestinationPlaylist(request, userId))
                    .isInstanceOf(RestClientException.class);

            verify(musicDestination, never()).addTrackToPlaylist(any(), any(), any());
        }
    }
}
