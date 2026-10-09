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
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPlaylistTrackDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyTrackDto;
import com.spotifyyoutube.migrator.playlist.api.dto.PlaylistResponse;
import com.spotifyyoutube.migrator.playlist.api.dto.TrackResponse;
import com.spotifyyoutube.migrator.playlist.domain.Playlist;
import com.spotifyyoutube.migrator.playlist.domain.Track;
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

    @Test
    void getPlaylistMetadata_shouldReturnMappedDomain() {
        when(tokenLifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.SPOTIFY)).thenReturn(connection);
        
        SpotifyPlaylistSummaryDto dto = new SpotifyPlaylistSummaryDto("pl123", "Name", null, null, null, null, null);
        when(spotifyApiClient.getPlaylistMetadata("valid_token", "pl123")).thenReturn(dto);

        Playlist mappedPlaylist = new Playlist();
        mappedPlaylist.setExternalId("pl123");
        mappedPlaylist.setName("Name");
        when(spotifyMapper.toPlaylistDomain(eq(dto), isNull())).thenReturn(mappedPlaylist);

        PlaylistResponse response = spotifyPlaylistService.getPlaylistMetadata(userId, "pl123");

        assertThat(response.externalId()).isEqualTo("pl123");
        assertThat(response.name()).isEqualTo("Name");
        verify(spotifyApiClient).getPlaylistMetadata("valid_token", "pl123");
    }

    @Test
    void getPlaylistTracks_shouldFetchAllPagesAndReturnTracks() {
        when(tokenLifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.SPOTIFY)).thenReturn(connection);

        // Page 1
        SpotifyTrackDto track1Dto = new SpotifyTrackDto("t1", "Track 1", 100, "uri", false, null, null);
        SpotifyPlaylistTrackDto item1 = new SpotifyPlaylistTrackDto(false, track1Dto);
        SpotifyPagingDto<SpotifyPlaylistTrackDto> page1 = new SpotifyPagingDto<>(
                "href", List.of(item1), 50, 0, "http://next", null, 100
        );
        when(spotifyApiClient.getPlaylistTracks("valid_token", "pl123", 50, 0)).thenReturn(page1);

        // Page 2
        SpotifyTrackDto track2Dto = new SpotifyTrackDto("t2", "Track 2", 100, "uri", false, null, null);
        SpotifyPlaylistTrackDto item2 = new SpotifyPlaylistTrackDto(false, track2Dto);
        SpotifyPagingDto<SpotifyPlaylistTrackDto> page2 = new SpotifyPagingDto<>(
                "href", List.of(item2), 50, 50, null, "http://prev", 100
        );
        when(spotifyApiClient.getPlaylistTracks("valid_token", "pl123", 50, 50)).thenReturn(page2);

        Track domainTrack1 = new Track(); domainTrack1.setExternalId("t1"); domainTrack1.setName("Track 1");
        Track domainTrack2 = new Track(); domainTrack2.setExternalId("t2"); domainTrack2.setName("Track 2");

        when(spotifyMapper.toTrackDomain(eq(track1Dto), any(Playlist.class))).thenReturn(domainTrack1);
        when(spotifyMapper.toTrackDomain(eq(track2Dto), any(Playlist.class))).thenReturn(domainTrack2);

        List<TrackResponse> responses = spotifyPlaylistService.getPlaylistTracks(userId, "pl123");

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).externalId()).isEqualTo("t1");
        assertThat(responses.get(1).externalId()).isEqualTo("t2");

        verify(spotifyApiClient).getPlaylistTracks("valid_token", "pl123", 50, 0);
        verify(spotifyApiClient).getPlaylistTracks("valid_token", "pl123", 50, 50);
    }

    @Test
    void getPlaylistTracks_shouldSkipLocalAndNullTracks() {
        when(tokenLifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.SPOTIFY)).thenReturn(connection);

        SpotifyTrackDto localTrackDto = new SpotifyTrackDto("lcl", "Local", 100, "uri", true, null, null);
        SpotifyPlaylistTrackDto localItem = new SpotifyPlaylistTrackDto(true, localTrackDto);
        SpotifyPlaylistTrackDto nullItem = new SpotifyPlaylistTrackDto(false, null);
        
        SpotifyPagingDto<SpotifyPlaylistTrackDto> page1 = new SpotifyPagingDto<>(
                "href", List.of(localItem, nullItem), 50, 0, null, null, 2
        );
        when(spotifyApiClient.getPlaylistTracks("valid_token", "pl123", 50, 0)).thenReturn(page1);

        List<TrackResponse> responses = spotifyPlaylistService.getPlaylistTracks(userId, "pl123");

        assertThat(responses).isEmpty(); // Both skipped
        verify(spotifyMapper, never()).toTrackDomain(any(), any());
    }
}
