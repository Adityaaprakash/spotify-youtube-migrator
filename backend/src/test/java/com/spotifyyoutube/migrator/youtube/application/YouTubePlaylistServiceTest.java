package com.spotifyyoutube.migrator.youtube.application;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.identity.application.OAuthTokenLifecycleService;
import com.spotifyyoutube.migrator.identity.domain.ConnectionStatus;
import com.spotifyyoutube.migrator.identity.domain.OAuthConnection;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.youtube.api.dto.AddVideoToPlaylistRequest;
import com.spotifyyoutube.migrator.youtube.api.dto.CreateYouTubePlaylistRequest;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubePlaylistItemResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubePlaylistResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubePrivacyStatus;
import com.spotifyyoutube.migrator.youtube.application.mapper.YouTubeMapper;
import com.spotifyyoutube.migrator.youtube.infrastructure.YouTubeClient;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistInsertRequestDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistItemDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YouTubePlaylistServiceTest {

    @Mock
    private YouTubeClient youTubeClient;

    @Mock
    private YouTubeMapper youTubeMapper;

    @Mock
    private OAuthTokenLifecycleService tokenLifecycleService;

    private YouTubePlaylistService playlistService;
    private UUID testUserId;

    @BeforeEach
    void setUp() {
        playlistService = new YouTubePlaylistService(youTubeClient, youTubeMapper, tokenLifecycleService);
        testUserId = UUID.randomUUID();
    }

    private OAuthConnection createMockConnection(ConnectionStatus status, String accessToken) {
        OAuthConnection connection = new OAuthConnection();
        connection.setId(UUID.randomUUID());
        connection.setProvider(OAuthProvider.GOOGLE);
        connection.setStatus(status);
        connection.setAccessToken(accessToken);
        return connection;
    }

    @Test
    void createPlaylist_shouldCallClientAndReturnMappedResponse_whenValid() {
        OAuthConnection connection = createMockConnection(ConnectionStatus.CONNECTED, "valid-google-token");
        when(tokenLifecycleService.refreshConnectionIfNeeded(testUserId, OAuthProvider.GOOGLE)).thenReturn(connection);

        YouTubePlaylistDto mockProviderDto = new YouTubePlaylistDto("youtube#playlist", "etag", "PL123", null, null);
        YouTubePlaylistResponse expectedResponse = new YouTubePlaylistResponse(
                "PL123", "Rock Classics", "Greatest rock songs", "private", "2023-01-01T00:00:00Z", "My Channel"
        );

        when(youTubeClient.createPlaylist(eq("valid-google-token"), any(YouTubePlaylistInsertRequestDto.class)))
                .thenReturn(mockProviderDto);
        when(youTubeMapper.toPlaylistResponse(mockProviderDto)).thenReturn(expectedResponse);

        CreateYouTubePlaylistRequest request = new CreateYouTubePlaylistRequest(
                "Rock Classics", "Greatest rock songs", YouTubePrivacyStatus.PRIVATE
        );

        YouTubePlaylistResponse result = playlistService.createPlaylist(testUserId, request);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo("PL123");
        assertThat(result.title()).isEqualTo("Rock Classics");

        ArgumentCaptor<YouTubePlaylistInsertRequestDto> captor = ArgumentCaptor.forClass(YouTubePlaylistInsertRequestDto.class);
        verify(youTubeClient).createPlaylist(eq("valid-google-token"), captor.capture());
        assertThat(captor.getValue().snippet().title()).isEqualTo("Rock Classics");
        assertThat(captor.getValue().snippet().description()).isEqualTo("Greatest rock songs");
        assertThat(captor.getValue().status().privacyStatus()).isEqualTo("private");
    }

    @Test
    void createPlaylist_shouldDefaultToPrivateStatus_whenPrivacyStatusIsNull() {
        OAuthConnection connection = createMockConnection(ConnectionStatus.CONNECTED, "valid-google-token");
        when(tokenLifecycleService.refreshConnectionIfNeeded(testUserId, OAuthProvider.GOOGLE)).thenReturn(connection);

        YouTubePlaylistDto mockProviderDto = new YouTubePlaylistDto("youtube#playlist", "etag", "PL123", null, null);
        when(youTubeClient.createPlaylist(eq("valid-google-token"), any(YouTubePlaylistInsertRequestDto.class)))
                .thenReturn(mockProviderDto);

        CreateYouTubePlaylistRequest request = new CreateYouTubePlaylistRequest("Title", null, null);
        playlistService.createPlaylist(testUserId, request);

        ArgumentCaptor<YouTubePlaylistInsertRequestDto> captor = ArgumentCaptor.forClass(YouTubePlaylistInsertRequestDto.class);
        verify(youTubeClient).createPlaylist(eq("valid-google-token"), captor.capture());
        assertThat(captor.getValue().status().privacyStatus()).isEqualTo("private");
    }

    @Test
    void createPlaylist_shouldThrowIllegalArgumentException_whenUserIdIsNull() {
        CreateYouTubePlaylistRequest request = new CreateYouTubePlaylistRequest("Title", "Desc", null);

        assertThatThrownBy(() -> playlistService.createPlaylist(null, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User ID must not be null");
    }

    @Test
    void createPlaylist_shouldThrowIllegalArgumentException_whenRequestIsNull() {
        assertThatThrownBy(() -> playlistService.createPlaylist(testUserId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Playlist request must not be null");
    }

    @Test
    void createPlaylist_shouldThrowIllegalArgumentException_whenTitleIsBlank() {
        CreateYouTubePlaylistRequest request = new CreateYouTubePlaylistRequest("   ", "Desc", null);

        assertThatThrownBy(() -> playlistService.createPlaylist(testUserId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Playlist title must not be blank");
    }

    @Test
    void createPlaylist_shouldThrowIllegalArgumentException_whenTitleExceedsLimit() {
        String longTitle = "a".repeat(151);
        CreateYouTubePlaylistRequest request = new CreateYouTubePlaylistRequest(longTitle, "Desc", null);

        assertThatThrownBy(() -> playlistService.createPlaylist(testUserId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Playlist title exceeds maximum length of 150 characters");
    }

    @Test
    void createPlaylist_shouldThrowIllegalArgumentException_whenDescriptionExceedsLimit() {
        String longDesc = "a".repeat(5001);
        CreateYouTubePlaylistRequest request = new CreateYouTubePlaylistRequest("Title", longDesc, null);

        assertThatThrownBy(() -> playlistService.createPlaylist(testUserId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Playlist description exceeds maximum length of 5000 characters");
    }

    @Test
    void createPlaylist_shouldThrowInvalidStateException_whenConnectionIsNull() {
        when(tokenLifecycleService.refreshConnectionIfNeeded(testUserId, OAuthProvider.GOOGLE)).thenReturn(null);

        CreateYouTubePlaylistRequest request = new CreateYouTubePlaylistRequest("Title", "Desc", null);

        assertThatThrownBy(() -> playlistService.createPlaylist(testUserId, request))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("Google account is not connected or requires reauthorization.");
    }

    @Test
    void createPlaylist_shouldThrowInvalidStateException_whenConnectionNotConnected() {
        OAuthConnection connection = createMockConnection(ConnectionStatus.REAUTH_REQUIRED, "expired-token");
        when(tokenLifecycleService.refreshConnectionIfNeeded(testUserId, OAuthProvider.GOOGLE)).thenReturn(connection);

        CreateYouTubePlaylistRequest request = new CreateYouTubePlaylistRequest("Title", "Desc", null);

        assertThatThrownBy(() -> playlistService.createPlaylist(testUserId, request))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("Google account is not connected or requires reauthorization.");
    }

    @Test
    void createPlaylist_shouldThrowInvalidStateException_whenAccessTokenIsBlank() {
        OAuthConnection connection = createMockConnection(ConnectionStatus.CONNECTED, "");
        when(tokenLifecycleService.refreshConnectionIfNeeded(testUserId, OAuthProvider.GOOGLE)).thenReturn(connection);

        CreateYouTubePlaylistRequest request = new CreateYouTubePlaylistRequest("Title", "Desc", null);

        assertThatThrownBy(() -> playlistService.createPlaylist(testUserId, request))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("Google access token is not available");
    }

    @Test
    void createPlaylist_shouldPropagateExternalProviderException_whenClientThrows() {
        OAuthConnection connection = createMockConnection(ConnectionStatus.CONNECTED, "valid-token");
        when(tokenLifecycleService.refreshConnectionIfNeeded(testUserId, OAuthProvider.GOOGLE)).thenReturn(connection);
        when(youTubeClient.createPlaylist(eq("valid-token"), any(YouTubePlaylistInsertRequestDto.class)))
                .thenThrow(new ExternalProviderException("YouTube quota exceeded", 403, null));

        CreateYouTubePlaylistRequest request = new CreateYouTubePlaylistRequest("Title", "Desc", null);

        assertThatThrownBy(() -> playlistService.createPlaylist(testUserId, request))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(403);
                });
    }

    @Test
    void addVideoToPlaylist_shouldCallClientAndReturnMappedResponse_whenValid() {
        OAuthConnection connection = createMockConnection(ConnectionStatus.CONNECTED, "valid-google-token");
        when(tokenLifecycleService.refreshConnectionIfNeeded(testUserId, OAuthProvider.GOOGLE)).thenReturn(connection);

        YouTubePlaylistItemDto mockItemDto = new YouTubePlaylistItemDto("youtube#playlistItem", "etag", "pli123", null);
        YouTubePlaylistItemResponse expectedResponse = new YouTubePlaylistItemResponse(
                "pli123", "PL123", "vid456", 0, true
        );

        when(youTubeClient.addVideoToPlaylist("valid-google-token", "PL123", "vid456", 0))
                .thenReturn(mockItemDto);
        when(youTubeMapper.toPlaylistItemResponse(mockItemDto)).thenReturn(expectedResponse);

        AddVideoToPlaylistRequest request = new AddVideoToPlaylistRequest("vid456", 0);
        YouTubePlaylistItemResponse result = playlistService.addVideoToPlaylist(testUserId, "PL123", request);

        assertThat(result).isNotNull();
        assertThat(result.playlistItemId()).isEqualTo("pli123");
        assertThat(result.videoId()).isEqualTo("vid456");
        assertThat(result.success()).isTrue();
        verify(youTubeClient).addVideoToPlaylist("valid-google-token", "PL123", "vid456", 0);
    }

    @Test
    void addVideoToPlaylist_shouldThrowIllegalArgumentException_whenUserIdIsNull() {
        AddVideoToPlaylistRequest request = new AddVideoToPlaylistRequest("vid456", 0);

        assertThatThrownBy(() -> playlistService.addVideoToPlaylist(null, "PL123", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User ID must not be null");
    }

    @Test
    void addVideoToPlaylist_shouldThrowIllegalArgumentException_whenPlaylistIdIsBlank() {
        AddVideoToPlaylistRequest request = new AddVideoToPlaylistRequest("vid456", 0);

        assertThatThrownBy(() -> playlistService.addVideoToPlaylist(testUserId, "   ", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Playlist ID must not be blank");
    }

    @Test
    void addVideoToPlaylist_shouldThrowIllegalArgumentException_whenRequestIsNull() {
        assertThatThrownBy(() -> playlistService.addVideoToPlaylist(testUserId, "PL123", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Add video request must not be null");
    }

    @Test
    void addVideoToPlaylist_shouldThrowIllegalArgumentException_whenVideoIdIsBlank() {
        AddVideoToPlaylistRequest request = new AddVideoToPlaylistRequest("   ", 0);

        assertThatThrownBy(() -> playlistService.addVideoToPlaylist(testUserId, "PL123", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Video ID must not be blank");
    }

    @Test
    void addVideoToPlaylist_shouldThrowIllegalArgumentException_whenPositionIsNegative() {
        AddVideoToPlaylistRequest request = new AddVideoToPlaylistRequest("vid123", -1);

        assertThatThrownBy(() -> playlistService.addVideoToPlaylist(testUserId, "PL123", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Position must be greater than or equal to 0");
    }

    @Test
    void addVideoToPlaylist_shouldThrowInvalidStateException_whenConnectionNotConnected() {
        OAuthConnection connection = createMockConnection(ConnectionStatus.DISCONNECTED, null);
        when(tokenLifecycleService.refreshConnectionIfNeeded(testUserId, OAuthProvider.GOOGLE)).thenReturn(connection);

        AddVideoToPlaylistRequest request = new AddVideoToPlaylistRequest("vid123", null);

        assertThatThrownBy(() -> playlistService.addVideoToPlaylist(testUserId, "PL123", request))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("Google account is not connected or requires reauthorization.");
    }

    @Test
    void addVideoToPlaylist_shouldThrowInvalidStateException_whenAccessTokenBlank() {
        OAuthConnection connection = createMockConnection(ConnectionStatus.CONNECTED, "  ");
        when(tokenLifecycleService.refreshConnectionIfNeeded(testUserId, OAuthProvider.GOOGLE)).thenReturn(connection);

        AddVideoToPlaylistRequest request = new AddVideoToPlaylistRequest("vid123", null);

        assertThatThrownBy(() -> playlistService.addVideoToPlaylist(testUserId, "PL123", request))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("Google access token is not available");
    }

    @Test
    void addVideoToPlaylist_shouldPropagateExternalProviderException_whenClientThrows() {
        OAuthConnection connection = createMockConnection(ConnectionStatus.CONNECTED, "valid-token");
        when(tokenLifecycleService.refreshConnectionIfNeeded(testUserId, OAuthProvider.GOOGLE)).thenReturn(connection);
        when(youTubeClient.addVideoToPlaylist("valid-token", "PL123", "vid123", null))
                .thenThrow(new ExternalProviderException("YouTube video not found", 404, null));

        AddVideoToPlaylistRequest request = new AddVideoToPlaylistRequest("vid123", null);

        assertThatThrownBy(() -> playlistService.addVideoToPlaylist(testUserId, "PL123", request))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(404);
                });
    }
}
