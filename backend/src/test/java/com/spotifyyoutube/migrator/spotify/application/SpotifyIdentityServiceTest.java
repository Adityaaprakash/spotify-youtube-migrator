package com.spotifyyoutube.migrator.spotify.application;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.identity.application.OAuthTokenLifecycleService;
import com.spotifyyoutube.migrator.identity.domain.ConnectionStatus;
import com.spotifyyoutube.migrator.identity.domain.OAuthConnection;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyProfileResponse;
import com.spotifyyoutube.migrator.spotify.application.mapper.SpotifyMapper;
import com.spotifyyoutube.migrator.spotify.infrastructure.SpotifyApiClient;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyUserProfileDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SpotifyIdentityServiceTest {

    @Mock
    private OAuthTokenLifecycleService tokenLifecycleService;

    @Mock
    private SpotifyApiClient spotifyApiClient;

    @Mock
    private SpotifyMapper spotifyMapper;

    @InjectMocks
    private SpotifyIdentityService spotifyIdentityService;

    private UUID userId;
    private OAuthConnection connection;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        connection = new OAuthConnection();
        connection.setId(UUID.randomUUID());
        connection.setUserId(userId);
        connection.setProvider(OAuthProvider.SPOTIFY);
        connection.setStatus(ConnectionStatus.CONNECTED);
        connection.setAccessToken("valid_token");
    }

    @Test
    void getConnectedProfile_shouldReturnProfile_whenConnected() {
        when(tokenLifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.SPOTIFY))
                .thenReturn(connection);

        SpotifyUserProfileDto  dto = new SpotifyUserProfileDto("spotify_user", "Spotify User", null, null);
        when(spotifyApiClient.getCurrentUserProfile("valid_token")).thenReturn(dto);

        SpotifyProfileResponse response = new SpotifyProfileResponse("spotify_user", "Spotify User", null, null);
        when(spotifyMapper.mapProfile(dto)).thenReturn(response);

        SpotifyProfileResponse result = spotifyIdentityService.getConnectedProfile(userId);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo("spotify_user");
        verify(tokenLifecycleService).refreshConnectionIfNeeded(userId, OAuthProvider.SPOTIFY);
        verify(spotifyApiClient).getCurrentUserProfile("valid_token");
    }

    @Test
    void getConnectedProfile_shouldThrow_whenReauthRequired() {
        connection.setStatus(ConnectionStatus.REAUTH_REQUIRED);
        when(tokenLifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.SPOTIFY))
                .thenReturn(connection);

        assertThatThrownBy(() -> spotifyIdentityService.getConnectedProfile(userId))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("reauthorization");

        verifyNoInteractions(spotifyApiClient);
    }
}
