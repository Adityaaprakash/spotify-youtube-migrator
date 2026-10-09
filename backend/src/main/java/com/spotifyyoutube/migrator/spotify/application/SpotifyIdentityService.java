package com.spotifyyoutube.migrator.spotify.application;

import com.spotifyyoutube.migrator.identity.application.OAuthTokenLifecycleService;
import com.spotifyyoutube.migrator.identity.domain.ConnectionStatus;
import com.spotifyyoutube.migrator.identity.domain.OAuthConnection;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.spotify.api.dto.SpotifyProfileResponse;
import com.spotifyyoutube.migrator.spotify.application.mapper.SpotifyMapper;
import com.spotifyyoutube.migrator.spotify.infrastructure.SpotifyApiClient;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyUserProfileDto;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class SpotifyIdentityService {

    private final OAuthTokenLifecycleService tokenLifecycleService;
    private final SpotifyApiClient spotifyApiClient;
    private final SpotifyMapper spotifyMapper;

    public SpotifyIdentityService(OAuthTokenLifecycleService tokenLifecycleService,
                                  SpotifyApiClient spotifyApiClient,
                                  SpotifyMapper spotifyMapper) {
        this.tokenLifecycleService = tokenLifecycleService;
        this.spotifyApiClient = spotifyApiClient;
        this.spotifyMapper = spotifyMapper;
    }

    public SpotifyProfileResponse getConnectedProfile(UUID userId) {
        OAuthConnection connection = tokenLifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.SPOTIFY);

        if (connection.getStatus() != ConnectionStatus.CONNECTED) {
            throw new com.spotifyyoutube.migrator.common.exception.InvalidStateException("Spotify connection requires reauthorization.");
        }

        SpotifyUserProfileDto profileDto = spotifyApiClient.getCurrentUserProfile(connection.getAccessToken());
        return spotifyMapper.mapProfile(profileDto);
    }
}
