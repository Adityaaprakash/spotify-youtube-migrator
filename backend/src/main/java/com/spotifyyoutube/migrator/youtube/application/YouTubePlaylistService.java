package com.spotifyyoutube.migrator.youtube.application;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.application.OAuthTokenLifecycleService;
import com.spotifyyoutube.migrator.identity.domain.ConnectionStatus;
import com.spotifyyoutube.migrator.identity.domain.OAuthConnection;
import com.spotifyyoutube.migrator.identity.domain.OAuthProvider;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistVerification;
import com.spotifyyoutube.migrator.migration.application.destination.model.PlaylistVerificationStatus;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
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
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistListResponseDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistSnippetDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistStatusDto;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

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

    public YouTubePlaylistResponse getPlaylist(UUID userId, String playlistId) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }
        if (playlistId == null || playlistId.isBlank()) {
            throw new IllegalArgumentException("Playlist ID must not be blank");
        }

        OAuthConnection connection = tokenLifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.GOOGLE);
        if (connection == null || connection.getStatus() != ConnectionStatus.CONNECTED) {
            throw new InvalidStateException("Google account is not connected or requires reauthorization.");
        }

        String accessToken = connection.getAccessToken();
        if (accessToken == null || accessToken.isBlank()) {
            throw new InvalidStateException("Google access token is not available. Please reconnect your Google account.");
        }

        YouTubePlaylistListResponseDto responseDto = youTubeClient.getPlaylist(accessToken, playlistId.trim());
        if (responseDto == null || responseDto.items() == null || responseDto.items().isEmpty()) {
            throw new ResourceNotFoundException("YouTube playlist not found: " + playlistId);
        }

        return youTubeMapper.toPlaylistResponse(responseDto.items().get(0));
    }

    public DestinationPlaylistVerification verifyPlaylist(UUID userId, String playlistId) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }
        if (playlistId == null || playlistId.isBlank()) {
            throw new IllegalArgumentException("Playlist ID must not be blank");
        }

        String trimmedPlaylistId = playlistId.trim();

        try {
            OAuthConnection connection = tokenLifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.GOOGLE);
            if (connection == null || connection.getStatus() != ConnectionStatus.CONNECTED) {
                return new DestinationPlaylistVerification(
                        trimmedPlaylistId,
                        PlaylistVerificationStatus.INACCESSIBLE,
                        "Google account is not connected or requires reauthorization.",
                        null
                );
            }

            String accessToken = connection.getAccessToken();
            if (accessToken == null || accessToken.isBlank()) {
                return new DestinationPlaylistVerification(
                        trimmedPlaylistId,
                        PlaylistVerificationStatus.INACCESSIBLE,
                        "Google access token is not available. Please reconnect your Google account.",
                        null
                );
            }

            YouTubePlaylistListResponseDto responseDto = youTubeClient.getPlaylist(accessToken, trimmedPlaylistId);
            if (responseDto == null || responseDto.items() == null || responseDto.items().isEmpty()) {
                return new DestinationPlaylistVerification(
                        trimmedPlaylistId,
                        PlaylistVerificationStatus.NOT_FOUND,
                        "Playlist does not exist or is unavailable.",
                        null
                );
            }

            YouTubePlaylistDto item = responseDto.items().get(0);
            String title = item.snippet() != null ? item.snippet().title() : null;
            String description = item.snippet() != null ? item.snippet().description() : null;
            String privacyStatus = item.status() != null ? item.status().privacyStatus() : null;
            String url = "https://www.youtube.com/playlist?list=" + item.id();

            DestinationPlaylist destinationPlaylist = new DestinationPlaylist(
                    item.id(),
                    title,
                    description,
                    privacyStatus,
                    url,
                    Platform.YOUTUBE
            );

            return new DestinationPlaylistVerification(
                    trimmedPlaylistId,
                    PlaylistVerificationStatus.ACCESSIBLE,
                    "Playlist exists and is accessible.",
                    destinationPlaylist
            );
        } catch (ResourceNotFoundException e) {
            return new DestinationPlaylistVerification(
                    trimmedPlaylistId,
                    PlaylistVerificationStatus.NOT_FOUND,
                    e.getMessage(),
                    null
            );
        } catch (InvalidStateException e) {
            return new DestinationPlaylistVerification(
                    trimmedPlaylistId,
                    PlaylistVerificationStatus.INACCESSIBLE,
                    e.getMessage(),
                    null
            );
        } catch (ExternalProviderException e) {
            int status = e.getProviderStatusCode() != null ? e.getProviderStatusCode() : 500;
            if (status == 401 || status == 403) {
                return new DestinationPlaylistVerification(
                        trimmedPlaylistId,
                        PlaylistVerificationStatus.INACCESSIBLE,
                        e.getMessage(),
                        null
                );
            } else if (status == 404) {
                return new DestinationPlaylistVerification(
                        trimmedPlaylistId,
                        PlaylistVerificationStatus.NOT_FOUND,
                        e.getMessage(),
                        null
                );
            } else if (status >= 500 || status == 429) {
                return new DestinationPlaylistVerification(
                        trimmedPlaylistId,
                        PlaylistVerificationStatus.UNCERTAIN,
                        e.getMessage(),
                        null
                );
            } else {
                return new DestinationPlaylistVerification(
                        trimmedPlaylistId,
                        PlaylistVerificationStatus.FAILED,
                        e.getMessage(),
                        null
                );
            }
        } catch (RestClientException e) {
            return new DestinationPlaylistVerification(
                    trimmedPlaylistId,
                    PlaylistVerificationStatus.UNCERTAIN,
                    "Upstream connection failure: " + e.getMessage(),
                    null
            );
        } catch (Exception e) {
            return new DestinationPlaylistVerification(
                    trimmedPlaylistId,
                    PlaylistVerificationStatus.FAILED,
                    "Unexpected verification failure: " + e.getMessage(),
                    null
            );
        }
    }
}
