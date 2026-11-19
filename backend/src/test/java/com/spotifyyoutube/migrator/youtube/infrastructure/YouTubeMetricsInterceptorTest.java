package com.spotifyyoutube.migrator.youtube.infrastructure;

import com.spotifyyoutube.migrator.common.metrics.MetricNames;
import com.spotifyyoutube.migrator.common.metrics.TagKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("YouTubeMetricsInterceptor Unit Tests")
class YouTubeMetricsInterceptorTest {

    private SimpleMeterRegistry registry;
    private YouTubeMetricsInterceptor interceptor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        interceptor = new YouTubeMetricsInterceptor(registry);
    }

    @Test
    @DisplayName("Rejects null MeterRegistry in constructor")
    void rejectsNullRegistry() {
        assertThatThrownBy(() -> new YouTubeMetricsInterceptor(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("MeterRegistry must not be null");
    }

    @Nested
    @DisplayName("Operation Resolution")
    class OperationResolutionTests {

        @Test
        @DisplayName("Resolves /search to search")
        void resolvesSearch() {
            HttpRequest req = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("https://www.googleapis.com/youtube/v3/search?q=test"));
            assertThat(YouTubeMetricsInterceptor.resolveOperation(req)).isEqualTo("search");
        }

        @Test
        @DisplayName("Resolves /videos to get_video")
        void resolvesVideos() {
            HttpRequest req = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("https://www.googleapis.com/youtube/v3/videos?id=abc12345678"));
            assertThat(YouTubeMetricsInterceptor.resolveOperation(req)).isEqualTo("get_video");
        }

        @Test
        @DisplayName("Resolves POST /playlists to create_playlist")
        void resolvesCreatePlaylist() {
            HttpRequest req = new MockClientHttpRequest(
                    HttpMethod.POST, URI.create("https://www.googleapis.com/youtube/v3/playlists?part=snippet"));
            assertThat(YouTubeMetricsInterceptor.resolveOperation(req)).isEqualTo("create_playlist");
        }

        @Test
        @DisplayName("Resolves GET /playlists with mine=true to get_my_playlists")
        void resolvesGetMyPlaylists() {
            HttpRequest req = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("https://www.googleapis.com/youtube/v3/playlists?part=snippet&mine=true"));
            assertThat(YouTubeMetricsInterceptor.resolveOperation(req)).isEqualTo("get_my_playlists");
        }

        @Test
        @DisplayName("Resolves GET /playlists without mine to get_playlist")
        void resolvesGetPlaylist() {
            HttpRequest req = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("https://www.googleapis.com/youtube/v3/playlists?part=snippet&id=PL123"));
            assertThat(YouTubeMetricsInterceptor.resolveOperation(req)).isEqualTo("get_playlist");
        }

        @Test
        @DisplayName("Resolves POST /playlistItems to add_playlist_item")
        void resolvesAddPlaylistItem() {
            HttpRequest req = new MockClientHttpRequest(
                    HttpMethod.POST, URI.create("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet"));
            assertThat(YouTubeMetricsInterceptor.resolveOperation(req)).isEqualTo("add_playlist_item");
        }

        @Test
        @DisplayName("Resolves GET /playlistItems to get_playlist_items")
        void resolvesGetPlaylistItems() {
            HttpRequest req = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&playlistId=PL123"));
            assertThat(YouTubeMetricsInterceptor.resolveOperation(req)).isEqualTo("get_playlist_items");
        }

        @Test
        @DisplayName("Resolves DELETE /playlistItems to remove_playlist_item")
        void resolvesRemovePlaylistItem() {
            HttpRequest req = new MockClientHttpRequest(
                    HttpMethod.DELETE, URI.create("https://www.googleapis.com/youtube/v3/playlistItems?id=PLI123"));
            assertThat(YouTubeMetricsInterceptor.resolveOperation(req)).isEqualTo("remove_playlist_item");
        }

        @Test
        @DisplayName("Resolves unrecognized path or null request to other")
        void resolvesFallback() {
            HttpRequest req = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("https://www.googleapis.com/youtube/v3/channels"));
            assertThat(YouTubeMetricsInterceptor.resolveOperation(req)).isEqualTo("other");
            assertThat(YouTubeMetricsInterceptor.resolveOperation(null)).isEqualTo("other");
        }
    }

    @Nested
    @DisplayName("Error Category Classification")
    class ErrorCategoryClassificationTests {

        @Test
        @DisplayName("Classifies 403 without Retry-After as auth_error")
        void classifies403() {
            assertThat(YouTubeMetricsInterceptor.classifyErrorCategory(403, null, null)).isEqualTo("auth_error");
        }

        @Test
        @DisplayName("Classifies 429 as rate_limit")
        void classifies429() {
            assertThat(YouTubeMetricsInterceptor.classifyErrorCategory(429, null, null)).isEqualTo("rate_limit");
        }

        @Test
        @DisplayName("Classifies Retry-After header presence as rate_limit")
        void classifiesRetryAfter() {
            assertThat(YouTubeMetricsInterceptor.classifyErrorCategory(503, "120", null)).isEqualTo("rate_limit");
        }

        @Test
        @DisplayName("Classifies 401 as auth_error")
        void classifiesAuth() {
            assertThat(YouTubeMetricsInterceptor.classifyErrorCategory(401, null, null)).isEqualTo("auth_error");
        }

        @Test
        @DisplayName("Classifies 400 and 422 as validation_error")
        void classifiesValidation() {
            assertThat(YouTubeMetricsInterceptor.classifyErrorCategory(400, null, null)).isEqualTo("validation_error");
            assertThat(YouTubeMetricsInterceptor.classifyErrorCategory(422, null, null)).isEqualTo("validation_error");
        }

        @Test
        @DisplayName("Classifies 404 and other 4xx as client_error")
        void classifiesClientError() {
            assertThat(YouTubeMetricsInterceptor.classifyErrorCategory(404, null, null)).isEqualTo("client_error");
            assertThat(YouTubeMetricsInterceptor.classifyErrorCategory(409, null, null)).isEqualTo("client_error");
        }

        @Test
        @DisplayName("Classifies 5xx as server_error")
        void classifiesServerError() {
            assertThat(YouTubeMetricsInterceptor.classifyErrorCategory(500, null, null)).isEqualTo("server_error");
            assertThat(YouTubeMetricsInterceptor.classifyErrorCategory(503, null, null)).isEqualTo("server_error");
        }

        @Test
        @DisplayName("Classifies SocketTimeoutException as timeout")
        void classifiesTimeout() {
            assertThat(YouTubeMetricsInterceptor.classifyErrorCategory(0, null, new SocketTimeoutException("Connect timed out")))
                    .isEqualTo("timeout");
        }

        @Test
        @DisplayName("Classifies IOException as network_error")
        void classifiesNetworkError() {
            assertThat(YouTubeMetricsInterceptor.classifyErrorCategory(0, null, new IOException("Broken pipe")))
                    .isEqualTo("network_error");
        }
    }

    @Nested
    @DisplayName("Intercept Execution & Metrics Recording")
    class InterceptExecutionTests {

        @Test
        @DisplayName("Records request counter and latency timer on successful search response")
        void recordsSuccessfulSearch() throws IOException {
            HttpRequest request = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("https://www.googleapis.com/youtube/v3/search?q=test"));
            byte[] body = new byte[0];

            ClientHttpRequestExecution execution = (req, b) -> new MockClientHttpResponse(new byte[0], HttpStatusCode.valueOf(200));

            ClientHttpResponse response = interceptor.intercept(request, body, execution);

            assertThat(response.getStatusCode().value()).isEqualTo(200);

            Counter requestCounter = registry.get(MetricNames.YOUTUBE_API_REQUESTS)
                    .tag(TagKeys.PROVIDER, "youtube")
                    .tag(TagKeys.OPERATION, "search")
                    .tag(TagKeys.STATUS, "200")
                    .counter();
            assertThat(requestCounter.count()).isEqualTo(1.0);

            Timer timer = registry.get(MetricNames.YOUTUBE_API_LATENCY)
                    .tag(TagKeys.PROVIDER, "youtube")
                    .tag(TagKeys.OPERATION, "search")
                    .tag(TagKeys.STATUS, "success")
                    .timer();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isGreaterThanOrEqualTo(0);

            assertThat(registry.find(MetricNames.YOUTUBE_API_ERRORS).counter()).isNull();
        }

        @Test
        @DisplayName("Records auth_error on HTTP 403 response without Retry-After")
        void records403Forbidden() throws IOException {
            HttpRequest request = new MockClientHttpRequest(
                    HttpMethod.POST, URI.create("https://www.googleapis.com/youtube/v3/playlistItems"));
            byte[] body = new byte[0];

            ClientHttpRequestExecution execution = (req, b) -> new MockClientHttpResponse(new byte[0], HttpStatusCode.valueOf(403));

            ClientHttpResponse response = interceptor.intercept(request, body, execution);

            assertThat(response.getStatusCode().value()).isEqualTo(403);

            Counter errorCounter = registry.get(MetricNames.YOUTUBE_API_ERRORS)
                    .tag(TagKeys.PROVIDER, "youtube")
                    .tag(TagKeys.OPERATION, "add_playlist_item")
                    .tag(TagKeys.STATUS, "403")
                    .tag(TagKeys.ERROR_CATEGORY, "auth_error")
                    .counter();
            assertThat(errorCounter.count()).isEqualTo(1.0);

            Timer timer = registry.get(MetricNames.YOUTUBE_API_LATENCY)
                    .tag(TagKeys.STATUS, "error")
                    .timer();
            assertThat(timer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("Records rate limit error on HTTP 429 response with Retry-After")
        void records429RateLimit() throws IOException {
            HttpRequest request = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("https://www.googleapis.com/youtube/v3/videos?id=123"));
            byte[] body = new byte[0];

            MockClientHttpResponse mockResponse = new MockClientHttpResponse(new byte[0], HttpStatusCode.valueOf(429));
            mockResponse.getHeaders().add(HttpHeaders.RETRY_AFTER, "60");
            ClientHttpRequestExecution execution = (req, b) -> mockResponse;

            interceptor.intercept(request, body, execution);

            Counter errorCounter = registry.get(MetricNames.YOUTUBE_API_ERRORS)
                    .tag(TagKeys.PROVIDER, "youtube")
                    .tag(TagKeys.OPERATION, "get_video")
                    .tag(TagKeys.STATUS, "429")
                    .tag(TagKeys.ERROR_CATEGORY, "rate_limit")
                    .counter();
            assertThat(errorCounter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Records timeout and rethrows when transport times out")
        void recordsTimeout() {
            HttpRequest request = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("https://www.googleapis.com/youtube/v3/search?q=test"));
            byte[] body = new byte[0];

            ClientHttpRequestExecution execution = (req, b) -> {
                throw new SocketTimeoutException("Read timed out");
            };

            assertThatThrownBy(() -> interceptor.intercept(request, body, execution))
                    .isInstanceOf(SocketTimeoutException.class)
                    .hasMessage("Read timed out");

            Counter requestCounter = registry.get(MetricNames.YOUTUBE_API_REQUESTS)
                    .tag(TagKeys.STATUS, "error")
                    .counter();
            assertThat(requestCounter.count()).isEqualTo(1.0);

            Counter errorCounter = registry.get(MetricNames.YOUTUBE_API_ERRORS)
                    .tag(TagKeys.ERROR_CATEGORY, "timeout")
                    .tag(TagKeys.STATUS, "error")
                    .counter();
            assertThat(errorCounter.count()).isEqualTo(1.0);

            Timer timer = registry.get(MetricNames.YOUTUBE_API_LATENCY)
                    .tag(TagKeys.STATUS, "error")
                    .timer();
            assertThat(timer.count()).isEqualTo(1);
        }
    }
}
