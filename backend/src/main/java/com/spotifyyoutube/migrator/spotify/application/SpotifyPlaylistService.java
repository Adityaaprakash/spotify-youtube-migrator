package com.spotifyyoutube.migrator.spotify.application;

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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Objects;

import com.spotifyyoutube.migrator.playlist.api.dto.PlaylistResponse;
import com.spotifyyoutube.migrator.playlist.api.dto.TrackResponse;
import com.spotifyyoutube.migrator.playlist.api.mapper.PlaylistMapper;
import com.spotifyyoutube.migrator.playlist.domain.Playlist;
import com.spotifyyoutube.migrator.playlist.domain.Track;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPlaylistTrackDto;

@Service
public class SpotifyPlaylistService {

    private final OAuthTokenLifecycleService tokenLifecycleService;
    private final SpotifyApiClient spotifyApiClient;
    private final SpotifyMapper spotifyMapper;

    public SpotifyPlaylistService(OAuthTokenLifecycleService tokenLifecycleService,
                                  SpotifyApiClient spotifyApiClient,
                                  SpotifyMapper spotifyMapper) {
        this.tokenLifecycleService = tokenLifecycleService;
        this.spotifyApiClient = spotifyApiClient;
        this.spotifyMapper = spotifyMapper;
    }

    public SpotifyPageResponse<SpotifyPlaylistSummaryResponse> getPlaylists(UUID userId, int limit, int offset) {
        if (limit > 50) {
            limit = 50; // Spotify max limit
        }
        if (limit < 1) {
            limit = 20; // Default limit
        }
        if (offset < 0) {
            offset = 0;
        }

        OAuthConnection connection = tokenLifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.SPOTIFY);

        if (connection.getStatus() != ConnectionStatus.CONNECTED) {
            throw new com.spotifyyoutube.migrator.common.exception.InvalidStateException("Spotify connection requires reauthorization.");
        }

        SpotifyPagingDto<SpotifyPlaylistSummaryDto> pagingDto = spotifyApiClient.getUserPlaylists(connection.getAccessToken(), limit, offset);

        List<SpotifyPlaylistSummaryResponse> items = pagingDto.items().stream()
                .map(spotifyMapper::mapPlaylistSummary)
                .toList();

        return spotifyMapper.mapPaging(pagingDto, items);
    }

    public PlaylistResponse getPlaylistMetadata(UUID userId, String playlistId) {
        OAuthConnection connection = tokenLifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.SPOTIFY);
        if (connection.getStatus() != ConnectionStatus.CONNECTED) {
            throw new com.spotifyyoutube.migrator.common.exception.InvalidStateException("Spotify connection requires reauthorization.");
        }

        SpotifyPlaylistSummaryDto dto = spotifyApiClient.getPlaylistMetadata(connection.getAccessToken(), playlistId);
        
        Playlist domainPlaylist = spotifyMapper.toPlaylistDomain(dto, null);
        return PlaylistMapper.toResponse(domainPlaylist);
    }

    public List<TrackResponse> getPlaylistTracks(UUID userId, String playlistId) {
        OAuthConnection connection = tokenLifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.SPOTIFY);
        if (connection.getStatus() != ConnectionStatus.CONNECTED) {
            throw new com.spotifyyoutube.migrator.common.exception.InvalidStateException("Spotify connection requires reauthorization.");
        }

        // We fetch the playlist metadata first just to pass as a reference, or we can use a dummy Playlist
        // But since this is a domain boundary, creating a dummy Playlist with the ID is sufficient for track mapping.
        Playlist dummyPlaylist = new Playlist();
        dummyPlaylist.setExternalId(playlistId);
        
        int offset = 0;
        int limit = 50; 
        SpotifyPagingDto<SpotifyPlaylistTrackDto> currentPaging;
        List<Track> allTracks = new ArrayList<>();

        do {
            currentPaging = spotifyApiClient.getPlaylistTracks(connection.getAccessToken(), playlistId, limit, offset);
            
            if (currentPaging.items() != null) {
                for (SpotifyPlaylistTrackDto item : currentPaging.items()) {
                    if (item == null || item.track() == null) {
                        continue;
                    }
                    if (Boolean.TRUE.equals(item.isLocal())) {
                        continue; // We skip local tracks since they aren't on the public platform
                    }
                    Track track = spotifyMapper.toTrackDomain(item.track(), dummyPlaylist);
                    if (track != null) {
                        allTracks.add(track);
                    }
                }
            }

            offset += limit;
        } while (currentPaging.next() != null && !currentPaging.next().isBlank() && (currentPaging.total() == null || offset < currentPaging.total()));

        return PlaylistMapper.toTrackResponseList(allTracks);
    }
}
