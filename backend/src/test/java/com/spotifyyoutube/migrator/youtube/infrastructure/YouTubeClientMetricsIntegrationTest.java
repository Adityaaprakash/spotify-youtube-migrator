package com.spotifyyoutube.migrator.youtube.infrastructure;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.metrics.MetricNames;
import com.spotifyyoutube.migrator.common.metrics.TagKeys;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistInsertRequestDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistSnippetDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistStatusDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeSearchListResponseDto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("YouTubeClient Metrics Integration Tests")
class YouTubeClientMetricsIntegrationTest {

    private SimpleMeterRegistry registry;
    private YouTubeClient youTubeClient;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        YouTubeMetricsInterceptor interceptor = new YouTubeMetricsInterceptor(registry);

        YouTubeProperties properties = new YouTubeProperties();
        properties.setBaseUrl("https://www.googleapis.com/youtube/v3");
        properties.setApiKey("test-api-key");

        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        youTubeClient = new YouTubeClient(builder, properties, interceptor);
    }

    @Test
    @DisplayName("searchVideos records youtube.api.requests counter and latency timer")
    void recordsSearchMetrics() {
        String mockResponse = """
        {
            "kind": "youtube#searchListResponse",
            "etag": "etag1",
            "pageInfo": { "totalResults": 10, "resultsPerPage": 5 },
            "items": []
        }
        """;

        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&q=test&maxResults=5&key=test-api-key"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(mockResponse, MediaType.APPLICATION_JSON));

        YouTubeSearchListResponseDto result = youTubeClient.searchVideos("test", 5, null);

        assertThat(result).isNotNull();
        mockServer.verify();

        Counter reqCounter = registry.get(MetricNames.YOUTUBE_API_REQUESTS)
                .tag(TagKeys.PROVIDER, "youtube")
                .tag(TagKeys.OPERATION, "search")
                .tag(TagKeys.STATUS, "200")
                .counter();
        assertThat(reqCounter.count()).isEqualTo(1.0);

        Timer timer = registry.get(MetricNames.YOUTUBE_API_LATENCY)
                .tag(TagKeys.PROVIDER, "youtube")
                .tag(TagKeys.OPERATION, "search")
                .tag(TagKeys.STATUS, "success")
                .timer();
        assertThat(timer.count()).isEqualTo(1);

        assertThat(registry.find(MetricNames.YOUTUBE_API_ERRORS).counter()).isNull();
    }

    @Test
    @DisplayName("createPlaylist records create_playlist operation metric")
    void recordsCreatePlaylistMetrics() {
        YouTubePlaylistInsertRequestDto requestDto = new YouTubePlaylistInsertRequestDto(
                new YouTubePlaylistSnippetDto("New Playlist", "Desc", null, null, null, null),
                new YouTubePlaylistStatusDto("private")
        );
        String responseJson = """
        {
            "kind": "youtube#playlist",
            "etag": "etag",
            "id": "PL123",
            "snippet": {
                "title": "New Playlist",
                "description": "Desc"
            },
            "status": {
                "privacyStatus": "private"
            }
        }
        """;

        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlists?part=snippet,status"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-yt"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        YouTubePlaylistDto playlist = youTubeClient.createPlaylist("token-yt", requestDto);

        assertThat(playlist).isNotNull();
        assertThat(playlist.id()).isEqualTo("PL123");
        mockServer.verify();

        Counter reqCounter = registry.get(MetricNames.YOUTUBE_API_REQUESTS)
                .tag(TagKeys.PROVIDER, "youtube")
                .tag(TagKeys.OPERATION, "create_playlist")
                .counter();
        assertThat(reqCounter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Forbidden error (HTTP 403) records auth_error error metric")
    void recordsForbiddenError() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&q=test&maxResults=5&key=test-api-key"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> youTubeClient.searchVideos("test", 5, null))
                .isInstanceOf(ExternalProviderException.class);
        mockServer.verify();

        Counter errCounter = registry.get(MetricNames.YOUTUBE_API_ERRORS)
                .tag(TagKeys.PROVIDER, "youtube")
                .tag(TagKeys.OPERATION, "search")
                .tag(TagKeys.STATUS, "403")
                .tag(TagKeys.ERROR_CATEGORY, "auth_error")
                .counter();
        assertThat(errCounter.count()).isEqualTo(1.0);

        Timer timer = registry.get(MetricNames.YOUTUBE_API_LATENCY)
                .tag(TagKeys.STATUS, "error")
                .timer();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Rate limit error (HTTP 429) records rate_limit error metric")
    void recordsRateLimitError() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "60");

        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&q=test&maxResults=5&key=test-api-key"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));

        assertThatThrownBy(() -> youTubeClient.searchVideos("test", 5, null))
                .isInstanceOf(ExternalProviderException.class);
        mockServer.verify();

        Counter errCounter = registry.get(MetricNames.YOUTUBE_API_ERRORS)
                .tag(TagKeys.PROVIDER, "youtube")
                .tag(TagKeys.STATUS, "429")
                .tag(TagKeys.ERROR_CATEGORY, "rate_limit")
                .counter();
        assertThat(errCounter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Retry attempt sequence records one metric increment per outbound HTTP attempt")
    void recordsRetryAttemptsPerHttpDispatch() {
        String mockResponse = """
        {
            "kind": "youtube#searchListResponse",
            "etag": "etag1",
            "pageInfo": { "totalResults": 1, "resultsPerPage": 1 },
            "items": []
        }
        """;

        // Attempt 1: 500 Server Error
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&q=retry&maxResults=1&key=test-api-key"))
                .andRespond(withServerError());

        // Attempt 2: 200 OK (simulated retry)
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&q=retry&maxResults=1&key=test-api-key"))
                .andRespond(withSuccess(mockResponse, MediaType.APPLICATION_JSON));

        // Attempt 1 fails
        assertThatThrownBy(() -> youTubeClient.searchVideos("retry", 1, null))
                .isInstanceOf(ExternalProviderException.class);

        // Attempt 2 succeeds
        YouTubeSearchListResponseDto secondResult = youTubeClient.searchVideos("retry", 1, null);
        assertThat(secondResult).isNotNull();
        mockServer.verify();

        // Total requests dispatched across attempts = 2 (1 failed with 500, 1 succeeded with 200)
        double totalRequests = registry.find(MetricNames.YOUTUBE_API_REQUESTS)
                .tag(TagKeys.PROVIDER, "youtube")
                .tag(TagKeys.OPERATION, "search")
                .counters()
                .stream()
                .mapToDouble(Counter::count)
                .sum();
        assertThat(totalRequests).isEqualTo(2.0);

        Counter failedAttemptCounter = registry.get(MetricNames.YOUTUBE_API_REQUESTS)
                .tag(TagKeys.PROVIDER, "youtube")
                .tag(TagKeys.OPERATION, "search")
                .tag(TagKeys.STATUS, "500")
                .counter();
        assertThat(failedAttemptCounter.count()).isEqualTo(1.0);

        Counter successAttemptCounter = registry.get(MetricNames.YOUTUBE_API_REQUESTS)
                .tag(TagKeys.PROVIDER, "youtube")
                .tag(TagKeys.OPERATION, "search")
                .tag(TagKeys.STATUS, "200")
                .counter();
        assertThat(successAttemptCounter.count()).isEqualTo(1.0);

        // Errors recorded = 1 (the 500)
        Counter errCounter = registry.get(MetricNames.YOUTUBE_API_ERRORS)
                .tag(TagKeys.PROVIDER, "youtube")
                .tag(TagKeys.OPERATION, "search")
                .tag(TagKeys.STATUS, "500")
                .counter();
        assertThat(errCounter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Empty search results response does not increment error counter")
    void emptySearchResultDoesNotIncrementErrors() {
        String emptyResponse = """
        {
            "kind": "youtube#searchListResponse",
            "etag": "etag",
            "pageInfo": { "totalResults": 0, "resultsPerPage": 0 },
            "items": []
        }
        """;

        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&q=nomatch&maxResults=5&key=test-api-key"))
                .andRespond(withSuccess(emptyResponse, MediaType.APPLICATION_JSON));

        YouTubeSearchListResponseDto result = youTubeClient.searchVideos("nomatch", 5, null);

        assertThat(result).isNotNull();
        assertThat(result.items()).isEmpty();
        mockServer.verify();

        Counter reqCounter = registry.get(MetricNames.YOUTUBE_API_REQUESTS)
                .tag(TagKeys.OPERATION, "search")
                .counter();
        assertThat(reqCounter.count()).isEqualTo(1.0);

        assertThat(registry.find(MetricNames.YOUTUBE_API_ERRORS).counter()).isNull();
    }
}
