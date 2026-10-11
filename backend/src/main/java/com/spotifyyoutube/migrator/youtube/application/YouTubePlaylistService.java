package com.spotifyyoutube.migrator.youtube.application;

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
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistSnippetDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistStatusDto;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class YouTubePlaylistService {

    public static final int MAX_TITLE_LENGTH = 150;
    public static final int MAX_DESCRIPTION_LENGTH = 5000;

    private final YouTubeClient youTubeClient;
    private final YouTubeMapper youTubeMapper;
    private final OAuthTokenLifecycleService tokenLifecycleService;

    public YouTubePlaylistService(YouTubeClient youTubeClient,
                                  YouTubeMapper youTubeMapper,
                                  OAuthTokenLifecycleService tokenLifecycleService) {
        this.youTubeClient = youTubeClient;
        this.youTubeMapper = youTubeMapper;
        this.tokenLifecycleService = tokenLifecycleService;
    }

    public YouTubePlaylistResponse createPlaylist(UUID userId, CreateYouTubePlaylistRequest request) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }
        if (request == null) {
            throw new IllegalArgumentException("Playlist request must not be null");
        }
        if (request.title() == null || request.title().isBlank()) {
            throw new IllegalArgumentException("Playlist title must not be blank");
        }

        String trimmedTitle = request.title().trim();
        if (trimmedTitle.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("Playlist title exceeds maximum length of " + MAX_TITLE_LENGTH + " characters");
        }

        String trimmedDescription = null;
        if (request.description() != null) {
            trimmedDescription = request.description().trim();
            if (trimmedDescription.length() > MAX_DESCRIPTION_LENGTH) {
                throw new IllegalArgumentException("Playlist description exceeds maximum length of " + MAX_DESCRIPTION_LENGTH + " characters");
            }
        }

        OAuthConnection connection = tokenLifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.GOOGLE);
        if (connection == null || connection.getStatus() != ConnectionStatus.CONNECTED) {
            throw new InvalidStateException("Google account is not connected or requires reauthorization.");
        }

        String accessToken = connection.getAccessToken();
        if (accessToken == null || accessToken.isBlank()) {
            throw new InvalidStateException("Google access token is not available. Please reconnect your Google account.");
        }

        String privacy = (request.privacyStatus() != null)
                ? request.privacyStatus().getApiValue()
                : YouTubePrivacyStatus.PRIVATE.getApiValue();

        YouTubePlaylistSnippetDto snippet = new YouTubePlaylistSnippetDto(
                trimmedTitle,
                trimmedDescription,
                null,
                null,
                null,
                null
        );
        YouTubePlaylistStatusDto status = new YouTubePlaylistStatusDto(privacy);
        YouTubePlaylistInsertRequestDto insertDto = new YouTubePlaylistInsertRequestDto(snippet, status);

        YouTubePlaylistDto playlistDto = youTubeClient.createPlaylist(accessToken, insertDto);
        return youTubeMapper.toPlaylistResponse(playlistDto);
    }

    public YouTubePlaylistItemResponse addVideoToPlaylist(UUID userId, String playlistId, AddVideoToPlaylistRequest request) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }
        if (playlistId == null || playlistId.isBlank()) {
            throw new IllegalArgumentException("Playlist ID must not be blank");
        }
        if (request == null) {
            throw new IllegalArgumentException("Add video request must not be null");
        }
        if (request.videoId() == null || request.videoId().isBlank()) {
            throw new IllegalArgumentException("Video ID must not be blank");
        }
        if (request.position() != null && request.position() < 0) {
            throw new IllegalArgumentException("Position must be greater than or equal to 0");
        }

        OAuthConnection connection = tokenLifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.GOOGLE);
        if (connection == null || connection.getStatus() != ConnectionStatus.CONNECTED) {
            throw new InvalidStateException("Google account is not connected or requires reauthorization.");
        }

        String accessToken = connection.getAccessToken();
        if (accessToken == null || accessToken.isBlank()) {
            throw new InvalidStateException("Google access token is not available. Please reconnect your Google account.");
        }

        YouTubePlaylistItemDto itemDto = youTubeClient.addVideoToPlaylist(
                accessToken,
                playlistId.trim(),
                request.videoId().trim(),
                request.position()
        );
        return youTubeMapper.toPlaylistItemResponse(itemDto);
    }
}
