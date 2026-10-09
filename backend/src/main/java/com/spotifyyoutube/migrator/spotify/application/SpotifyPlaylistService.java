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

import java.util.List;
import java.util.UUID;

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
}
