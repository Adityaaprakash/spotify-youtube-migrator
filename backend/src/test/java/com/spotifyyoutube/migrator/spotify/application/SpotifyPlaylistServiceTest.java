package com.spotifyyoutube.migrator.spotify.application;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.identity.application.OAuthTokenLifecycleService;
import com.spotifyyoutube.migrator.identity.domain.ConnectionStatus;
import com.spotifyyoutube.migrator.identity.domain.OAuthConnection;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyPageResponse;
import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyPlaylistSummaryResponse;
import com.spotifyyoutube.migrator.spotify.application.mapper.SpotifyMapper;
import com.spotifyyoutube.migrator.spotify.infrastructure.SpotifyApiClient;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPagingDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPlaylistSummaryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SpotifyPlaylistServiceTest {

    @Mock
    private OAuthTokenLifecycleService tokenLifecycleService;

    @Mock
    private SpotifyApiClient spotifyApiClient;

    @Mock
    private SpotifyMapper spotifyMapper;

    @InjectMocks
    private SpotifyPlaylistService spotifyPlaylistService;

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
    void getPlaylists_shouldReturnMappedPaging_whenConnected() {
        when(tokenLifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.SPOTIFY))
                .thenReturn(connection);

        SpotifyPagingDto<SpotifyPlaylistSummaryDto> pagingDto = new SpotifyPagingDto<>(
                "href", List.of(), 50, 0, null, null, 0
        );
        when(spotifyApiClient.getUserPlaylists("valid_token", 50, 0)).thenReturn(pagingDto);

        SpotifyPageResponse<SpotifyPlaylistSummaryResponse> pageResponse = new SpotifyPageResponse<>(
                List.of(), 0, 50, 0, false
        );
        when(spotifyMapper.<SpotifyPlaylistSummaryDto, SpotifyPlaylistSummaryResponse>mapPaging(eq(pagingDto), any())).thenReturn(pageResponse);

        SpotifyPageResponse<SpotifyPlaylistSummaryResponse> result = spotifyPlaylistService.getPlaylists(userId, 50, 0);

        assertThat(result).isNotNull();
        assertThat(result.total()).isEqualTo(0);
        verify(tokenLifecycleService).refreshConnectionIfNeeded(userId, OAuthProvider.SPOTIFY);
        verify(spotifyApiClient).getUserPlaylists("valid_token", 50, 0);
    }

    @Test
    void getPlaylists_shouldCapLimitAt50() {
        when(tokenLifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.SPOTIFY)).thenReturn(connection);
        
        SpotifyPagingDto<SpotifyPlaylistSummaryDto> pagingDto = new SpotifyPagingDto<>(
                "href", List.of(), 50, 0, null, null, 0
        );
        when(spotifyApiClient.getUserPlaylists("valid_token", 50, 0)).thenReturn(pagingDto);
        when(spotifyMapper.<SpotifyPlaylistSummaryDto, SpotifyPlaylistSummaryResponse>mapPaging(eq(pagingDto), any())).thenReturn(new SpotifyPageResponse<>(List.of(), 0, 50, 0, false));

        spotifyPlaylistService.getPlaylists(userId, 100, 0);

        verify(spotifyApiClient).getUserPlaylists("valid_token", 50, 0);
    }
}
