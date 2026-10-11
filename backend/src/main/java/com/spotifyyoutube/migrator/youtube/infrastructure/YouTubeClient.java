package com.spotifyyoutube.migrator.youtube.infrastructure;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistItemDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistItemInsertRequestDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistItemSnippetRequestDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistInsertRequestDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistListResponseDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeResourceIdDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeSearchListResponseDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoListResponseDto;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class YouTubeClient {

    private final RestClient restClient;
    private final YouTubeProperties properties;

    public YouTubeClient(RestClient.Builder restClientBuilder, YouTubeProperties properties) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(properties.getBaseUrl())
                .build();
    }

    public YouTubeSearchListResponseDto searchVideos(String query, int maxResults, String pageToken) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query must not be blank");
        }

        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new InvalidStateException("YouTube API key is not configured");
        }

        return restClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/search")
                            .queryParam("part", "snippet")
                            .queryParam("type", "video")
                            .queryParam("q", query)
                            .queryParam("maxResults", maxResults)
                            .queryParam("key", apiKey);
                    if (pageToken != null && !pageToken.isBlank()) {
                        builder.queryParam("pageToken", pageToken);
                    }
                    return builder.build();
                })
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    int statusCode = res.getStatusCode().value();
                    String retryAfter = res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);

                    if (statusCode == 404) {
                        throw new ResourceNotFoundException("YouTube search endpoint or resource not found");
                    }
                    if (statusCode == 401) {
                        throw new ExternalProviderException("YouTube API authentication failed: invalid API key", 401);
                    }
                    if (statusCode == 403) {
                        throw new ExternalProviderException("YouTube API request forbidden or quota exceeded", 403, retryAfter);
                    }
                    if (statusCode == 429) {
                        throw new ExternalProviderException("YouTube API rate limit exceeded", 429, retryAfter);
                    }
                    if (statusCode == 400) {
                        throw new ExternalProviderException("YouTube API request invalid", 400);
                    }
                    throw new ExternalProviderException("Failed to search YouTube videos", statusCode, retryAfter);
                })
                .body(YouTubeSearchListResponseDto.class);
    }

    public YouTubeVideoListResponseDto getVideoMetadata(String videoId) {
        if (videoId == null || videoId.isBlank()) {
            throw new IllegalArgumentException("Video ID must not be blank");
        }

        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new InvalidStateException("YouTube API key is not configured");
        }

        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/videos")
                        .queryParam("part", "snippet,contentDetails")
                        .queryParam("id", videoId)
                        .queryParam("key", apiKey)
                        .build())
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    int statusCode = res.getStatusCode().value();
                    String retryAfter = res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);

                    if (statusCode == 404) {
                        throw new ResourceNotFoundException("YouTube video endpoint or resource not found");
                    }
                    if (statusCode == 401) {
                        throw new ExternalProviderException("YouTube API authentication failed: invalid API key", 401);
                    }
                    if (statusCode == 403) {
                        throw new ExternalProviderException("YouTube API request forbidden or quota exceeded", 403, retryAfter);
                    }
                    if (statusCode == 429) {
                        throw new ExternalProviderException("YouTube API rate limit exceeded", 429, retryAfter);
                    }
                    if (statusCode == 400) {
                        throw new ExternalProviderException("YouTube API request invalid", 400);
                    }
                    throw new ExternalProviderException("Failed to retrieve YouTube video metadata", statusCode, retryAfter);
                })
                .body(YouTubeVideoListResponseDto.class);
    }

    public YouTubePlaylistDto createPlaylist(String accessToken, YouTubePlaylistInsertRequestDto requestDto) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Access token must not be blank");
        }
        if (requestDto == null) {
            throw new IllegalArgumentException("Playlist request body must not be null");
        }

        return restClient.post()
                .uri(uriBuilder -> uriBuilder.path("/playlists")
                        .queryParam("part", "snippet,status")
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestDto)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    int statusCode = res.getStatusCode().value();
                    String retryAfter = res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);

                    if (statusCode == 404) {
                        throw new ResourceNotFoundException("YouTube playlist endpoint not found");
                    }
                    if (statusCode == 401) {
                        throw new ExternalProviderException("YouTube API authentication failed: invalid or expired access token", 401);
                    }
                    if (statusCode == 403) {
                        throw new ExternalProviderException("YouTube API request forbidden: insufficient permissions or quota exceeded", 403, retryAfter);
                    }
                    if (statusCode == 429) {
                        throw new ExternalProviderException("YouTube API rate limit exceeded", 429, retryAfter);
                    }
                    if (statusCode == 400) {
                        throw new ExternalProviderException("YouTube API request invalid", 400);
                    }
                    throw new ExternalProviderException("Failed to create YouTube playlist", statusCode, retryAfter);
                })
                .body(YouTubePlaylistDto.class);
    }

    public YouTubePlaylistItemDto addVideoToPlaylist(String accessToken, String playlistId, String videoId, Integer position) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Access token must not be blank");
        }
        if (playlistId == null || playlistId.isBlank()) {
            throw new IllegalArgumentException("Playlist ID must not be blank");
        }
        if (videoId == null || videoId.isBlank()) {
            throw new IllegalArgumentException("Video ID must not be blank");
        }

        YouTubePlaylistItemSnippetRequestDto snippet = new YouTubePlaylistItemSnippetRequestDto(
                playlistId,
                new YouTubeResourceIdDto("youtube#video", videoId, null, null),
                position
        );
        YouTubePlaylistItemInsertRequestDto requestDto = new YouTubePlaylistItemInsertRequestDto(snippet);

        return restClient.post()
                .uri(uriBuilder -> uriBuilder.path("/playlistItems")
                        .queryParam("part", "snippet")
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestDto)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    int statusCode = res.getStatusCode().value();
                    String retryAfter = res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);

                    if (statusCode == 404) {
                        throw new ResourceNotFoundException("YouTube playlist or video not found");
                    }
                    if (statusCode == 401) {
                        throw new ExternalProviderException("YouTube API authentication failed: invalid or expired access token", 401);
                    }
                    if (statusCode == 403) {
                        throw new ExternalProviderException("YouTube API request forbidden: insufficient permissions or quota exceeded", 403, retryAfter);
                    }
                    if (statusCode == 429) {
                        throw new ExternalProviderException("YouTube API rate limit exceeded", 429, retryAfter);
                    }
                    if (statusCode == 400) {
                        throw new ExternalProviderException("YouTube API request invalid", 400);
                    }
                    throw new ExternalProviderException("Failed to add video to YouTube playlist", statusCode, retryAfter);
                })
                .body(YouTubePlaylistItemDto.class);
    }

    public YouTubePlaylistListResponseDto getPlaylist(String accessToken, String playlistId) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Access token must not be blank");
        }
        if (playlistId == null || playlistId.isBlank()) {
            throw new IllegalArgumentException("Playlist ID must not be blank");
        }

        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/playlists")
                        .queryParam("part", "snippet,status")
                        .queryParam("id", playlistId.trim())
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    int statusCode = res.getStatusCode().value();
                    String retryAfter = res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);

                    if (statusCode == 404) {
                        throw new ResourceNotFoundException("YouTube playlist endpoint not found");
                    }
                    if (statusCode == 401) {
                        throw new ExternalProviderException("YouTube API authentication failed: invalid or expired access token", 401);
                    }
                    if (statusCode == 403) {
                        throw new ExternalProviderException("YouTube API request forbidden: insufficient permissions or quota exceeded", 403, retryAfter);
                    }
                    if (statusCode == 429) {
                        throw new ExternalProviderException("YouTube API rate limit exceeded", 429, retryAfter);
                    }
                    if (statusCode == 400) {
                        throw new ExternalProviderException("YouTube API request invalid", 400);
                    }
                    throw new ExternalProviderException("Failed to retrieve YouTube playlist", statusCode, retryAfter);
                })
                .body(YouTubePlaylistListResponseDto.class);
    }
}
