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
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistInspection;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItemDetails;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistItemListResponseDto;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    public void removeVideoFromPlaylist(UUID userId, String playlistItemId) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }
        if (playlistItemId == null || playlistItemId.isBlank()) {
            throw new IllegalArgumentException("Playlist item ID must not be blank");
        }

        OAuthConnection connection = tokenLifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.GOOGLE);
        if (connection == null || connection.getStatus() != ConnectionStatus.CONNECTED) {
            throw new InvalidStateException("Google account is not connected or requires reauthorization.");
        }

        String accessToken = connection.getAccessToken();
        if (accessToken == null || accessToken.isBlank()) {
            throw new InvalidStateException("Google access token is not available. Please reconnect your Google account.");
        }

        youTubeClient.removeVideoFromPlaylist(accessToken, playlistItemId.trim());
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

    public static final int MAX_INSPECTION_PAGES = 100;

    public YouTubePlaylistItemListResponseDto getPlaylistItems(UUID userId, String playlistId, int maxResults, String pageToken) {
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

        return youTubeClient.getPlaylistItems(accessToken, playlistId.trim(), maxResults, pageToken);
    }

    public DestinationPlaylistInspection inspectPlaylist(UUID userId, String playlistId) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }
        if (playlistId == null || playlistId.isBlank()) {
            throw new IllegalArgumentException("Playlist ID must not be blank");
        }

        String trimmedPlaylistId = playlistId.trim();
        Instant startedAt = Instant.now();

        OAuthConnection connection;
        try {
            connection = tokenLifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.GOOGLE);
        } catch (Exception ex) {
            return DestinationPlaylistInspection.failed(trimmedPlaylistId, "Failed to resolve Google credentials: " + ex.getMessage(), startedAt);
        }

        if (connection == null || connection.getStatus() != ConnectionStatus.CONNECTED) {
            return DestinationPlaylistInspection.failed(trimmedPlaylistId, "Google account is not connected or requires reauthorization.", startedAt);
        }

        String accessToken = connection.getAccessToken();
        if (accessToken == null || accessToken.isBlank()) {
            return DestinationPlaylistInspection.failed(trimmedPlaylistId, "Google access token is not available. Please reconnect your Google account.", startedAt);
        }

        Set<String> seenPageTokens = new HashSet<>();
        String currentPageToken = null;
        List<DestinationPlaylistItemDetails> allItems = new ArrayList<>();
        int pageCount = 0;
        boolean completedCleanly = false;

        try {
            while (pageCount < MAX_INSPECTION_PAGES) {
                pageCount++;
                YouTubePlaylistItemListResponseDto responseDto = youTubeClient.getPlaylistItems(accessToken, trimmedPlaylistId, 50, currentPageToken);
                if (responseDto == null) {
                    return DestinationPlaylistInspection.failed(trimmedPlaylistId, "Provider returned null response on page " + pageCount, startedAt);
                }

                List<YouTubePlaylistItemDto> items = responseDto.items();
                if (items != null) {
                    for (YouTubePlaylistItemDto item : items) {
                        if (item == null || item.id() == null || item.id().isBlank()) {
                            continue;
                        }
                        String playlistItemId = item.id().trim();
                        String videoId = null;
                        Integer position = null;
                        Instant publishedAt = null;
                        String title = null;

                        if (item.snippet() != null) {
                            if (item.snippet().resourceId() != null && item.snippet().resourceId().videoId() != null) {
                                videoId = item.snippet().resourceId().videoId().trim();
                            }
                            position = item.snippet().position();
                            title = item.snippet().title();
                            if (item.snippet().publishedAt() != null && !item.snippet().publishedAt().isBlank()) {
                                try {
                                    publishedAt = Instant.parse(item.snippet().publishedAt().trim());
                                } catch (Exception ignored) {
                                    // Non-standard format or unparseable; leave null
                                }
                            }
                        }

                        allItems.add(new DestinationPlaylistItemDetails(
                                playlistItemId,
                                trimmedPlaylistId,
                                videoId,
                                position,
                                publishedAt,
                                title
                        ));
                    }
                }

                String nextPageToken = responseDto.nextPageToken();
                if (nextPageToken == null || nextPageToken.isBlank()) {
                    completedCleanly = true;
                    break;
                }

                if (seenPageTokens.contains(nextPageToken)) {
                    return DestinationPlaylistInspection.failed(trimmedPlaylistId, "Cycle detected in provider pagination token: " + nextPageToken, startedAt);
                }

                seenPageTokens.add(nextPageToken);
                currentPageToken = nextPageToken;
            }

            if (!completedCleanly) {
                return DestinationPlaylistInspection.failed(trimmedPlaylistId, "Inspection exceeded maximum pagination limit of " + MAX_INSPECTION_PAGES + " pages", startedAt);
            }

            return DestinationPlaylistInspection.complete(trimmedPlaylistId, allItems, startedAt);

        } catch (ResourceNotFoundException ex) {
            return DestinationPlaylistInspection.failed(trimmedPlaylistId, "Playlist not found: " + ex.getMessage(), startedAt);
        } catch (ExternalProviderException ex) {
            return DestinationPlaylistInspection.failed(trimmedPlaylistId, "Provider error HTTP " + ex.getProviderStatusCode() + ": " + ex.getMessage(), startedAt);
        } catch (ResourceAccessException ex) {
            return DestinationPlaylistInspection.failed(trimmedPlaylistId, "Connection timeout during playlist inspection: " + ex.getMessage(), startedAt);
        } catch (RestClientException ex) {
            return DestinationPlaylistInspection.failed(trimmedPlaylistId, "Provider communication failure: " + ex.getMessage(), startedAt);
        } catch (Exception ex) {
            return DestinationPlaylistInspection.failed(trimmedPlaylistId, "Unexpected inspection failure: " + ex.getMessage(), startedAt);
        }
    }

    /**
     * Retrieves all destination playlists owned by the authenticated user with complete pagination (Phase 13F).
     * Used for post-failure playlist creation reconciliation.
     *
     * @param userId user identifier possessing YouTube authorization
     * @return unmodifiable list of destination playlists
     */
    public List<DestinationPlaylist> getUserPlaylists(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }

        OAuthConnection connection = tokenLifecycleService.refreshConnectionIfNeeded(userId, OAuthProvider.GOOGLE);
        if (connection == null || connection.getStatus() != ConnectionStatus.CONNECTED) {
            throw new InvalidStateException("Google account is not connected or requires reauthorization.");
        }

        String accessToken = connection.getAccessToken();
        if (accessToken == null || accessToken.isBlank()) {
            throw new InvalidStateException("Google access token is not available. Please reconnect your Google account.");
        }

        Set<String> seenPageTokens = new HashSet<>();
        String currentPageToken = null;
        List<DestinationPlaylist> allPlaylists = new ArrayList<>();
        int pageCount = 0;
        boolean completedCleanly = false;

        while (pageCount < MAX_INSPECTION_PAGES) {
            pageCount++;
            YouTubePlaylistListResponseDto responseDto = youTubeClient.getMyPlaylists(accessToken, 50, currentPageToken);
            if (responseDto == null) {
                throw new ExternalProviderException("YouTube returned null playlist response on page " + pageCount);
            }
            if (responseDto.items() == null) {
                throw new ExternalProviderException("YouTube returned null playlist items collection on page " + pageCount);
            }

            for (YouTubePlaylistDto item : responseDto.items()) {
                if (item == null || item.id() == null || item.id().isBlank()) {
                    continue;
                }
                String playlistId = item.id().trim();
                String title = item.snippet() != null ? item.snippet().title() : null;
                String description = item.snippet() != null ? item.snippet().description() : null;
                String privacy = item.status() != null ? item.status().privacyStatus() : null;
                String url = "https://www.youtube.com/playlist?list=" + playlistId;

                allPlaylists.add(new DestinationPlaylist(
                        playlistId,
                        title,
                        description,
                        privacy,
                        url,
                        Platform.YOUTUBE
                ));
            }

            String nextPageToken = responseDto.nextPageToken();
            if (nextPageToken == null || nextPageToken.isBlank()) {
                completedCleanly = true;
                break;
            }

            if (seenPageTokens.contains(nextPageToken)) {
                throw new ExternalProviderException("Detected cycle in YouTube playlist pagination tokens: " + nextPageToken);
            }

            seenPageTokens.add(nextPageToken);
            currentPageToken = nextPageToken;
        }

        if (!completedCleanly) {
            throw new ExternalProviderException("YouTube playlist inspection exceeded maximum limit of " + MAX_INSPECTION_PAGES + " pages");
        }

        return Collections.unmodifiableList(allPlaylists);
    }
}
