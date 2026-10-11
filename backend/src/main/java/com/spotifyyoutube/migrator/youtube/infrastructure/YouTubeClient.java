package com.spotifyyoutube.migrator.youtube.infrastructure;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeSearchListResponseDto;
import org.springframework.http.HttpHeaders;
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
}
