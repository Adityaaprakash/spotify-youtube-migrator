package com.spotifyyoutube.migrator.spotify.infrastructure;

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

@DisplayName("SpotifyMetricsInterceptor Unit Tests")
class SpotifyMetricsInterceptorTest {

    private SimpleMeterRegistry registry;
    private SpotifyMetricsInterceptor interceptor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        interceptor = new SpotifyMetricsInterceptor(registry);
    }

    @Test
    @DisplayName("Rejects null MeterRegistry in constructor")
    void rejectsNullRegistry() {
        assertThatThrownBy(() -> new SpotifyMetricsInterceptor(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("MeterRegistry must not be null");
    }

    @Nested
    @DisplayName("Operation Resolution")
    class OperationResolutionTests {

        @Test
        @DisplayName("Resolves /v1/me and /me to get_profile")
        void resolvesProfile() {
            HttpRequest req1 = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.spotify.com/v1/me"));
            HttpRequest req2 = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.spotify.com/me"));

            assertThat(SpotifyMetricsInterceptor.resolveOperation(req1)).isEqualTo("get_profile");
            assertThat(SpotifyMetricsInterceptor.resolveOperation(req2)).isEqualTo("get_profile");
        }

        @Test
        @DisplayName("Resolves /v1/me/playlists and /me/playlists to get_playlists")
        void resolvesPlaylists() {
            HttpRequest req1 = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("https://api.spotify.com/v1/me/playlists?limit=50&offset=0"));
            HttpRequest req2 = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("https://api.spotify.com/me/playlists?limit=20"));

            assertThat(SpotifyMetricsInterceptor.resolveOperation(req1)).isEqualTo("get_playlists");
            assertThat(SpotifyMetricsInterceptor.resolveOperation(req2)).isEqualTo("get_playlists");
        }

        @Test
        @DisplayName("Resolves /v1/playlists/{id}/tracks to get_playlist_tracks")
        void resolvesPlaylistTracks() {
            HttpRequest req = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("https://api.spotify.com/v1/playlists/37i9dQZF1DXcBWIGoYBM5M/tracks"));

            assertThat(SpotifyMetricsInterceptor.resolveOperation(req)).isEqualTo("get_playlist_tracks");
        }

        @Test
        @DisplayName("Resolves /v1/playlists/{id} to get_playlist")
        void resolvesPlaylistMetadata() {
            HttpRequest req = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("https://api.spotify.com/v1/playlists/37i9dQZF1DXcBWIGoYBM5M"));

            assertThat(SpotifyMetricsInterceptor.resolveOperation(req)).isEqualTo("get_playlist");
        }

        @Test
        @DisplayName("Resolves /token to token")
        void resolvesToken() {
            HttpRequest req = new MockClientHttpRequest(
                    HttpMethod.POST, URI.create("https://accounts.spotify.com/api/token"));

            assertThat(SpotifyMetricsInterceptor.resolveOperation(req)).isEqualTo("token");
        }

        @Test
        @DisplayName("Resolves unrecognized path or null request to other")
        void resolvesFallback() {
            HttpRequest req = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("https://api.spotify.com/v1/browse/new-releases"));

            assertThat(SpotifyMetricsInterceptor.resolveOperation(req)).isEqualTo("other");
            assertThat(SpotifyMetricsInterceptor.resolveOperation(null)).isEqualTo("other");
        }
    }

    @Nested
    @DisplayName("Error Category Classification")
    class ErrorCategoryClassificationTests {

        @Test
        @DisplayName("Classifies 429 as rate_limit")
        void classifies429() {
            assertThat(SpotifyMetricsInterceptor.classifyErrorCategory(429, null, null)).isEqualTo("rate_limit");
        }

        @Test
        @DisplayName("Classifies Retry-After header presence as rate_limit")
        void classifiesRetryAfter() {
            assertThat(SpotifyMetricsInterceptor.classifyErrorCategory(503, "30", null)).isEqualTo("rate_limit");
        }

        @Test
        @DisplayName("Classifies 401 and 403 as auth_error")
        void classifiesAuth() {
            assertThat(SpotifyMetricsInterceptor.classifyErrorCategory(401, null, null)).isEqualTo("auth_error");
            assertThat(SpotifyMetricsInterceptor.classifyErrorCategory(403, null, null)).isEqualTo("auth_error");
        }

        @Test
        @DisplayName("Classifies 400 and 422 as validation_error")
        void classifiesValidation() {
            assertThat(SpotifyMetricsInterceptor.classifyErrorCategory(400, null, null)).isEqualTo("validation_error");
            assertThat(SpotifyMetricsInterceptor.classifyErrorCategory(422, null, null)).isEqualTo("validation_error");
        }

        @Test
        @DisplayName("Classifies 404 and other 4xx as client_error")
        void classifiesClientError() {
            assertThat(SpotifyMetricsInterceptor.classifyErrorCategory(404, null, null)).isEqualTo("client_error");
            assertThat(SpotifyMetricsInterceptor.classifyErrorCategory(409, null, null)).isEqualTo("client_error");
        }

        @Test
        @DisplayName("Classifies 5xx as server_error")
        void classifiesServerError() {
            assertThat(SpotifyMetricsInterceptor.classifyErrorCategory(500, null, null)).isEqualTo("server_error");
            assertThat(SpotifyMetricsInterceptor.classifyErrorCategory(502, null, null)).isEqualTo("server_error");
            assertThat(SpotifyMetricsInterceptor.classifyErrorCategory(503, null, null)).isEqualTo("server_error");
        }

        @Test
        @DisplayName("Classifies SocketTimeoutException as timeout")
        void classifiesTimeout() {
            assertThat(SpotifyMetricsInterceptor.classifyErrorCategory(0, null, new SocketTimeoutException("Read timed out")))
                    .isEqualTo("timeout");
        }

        @Test
        @DisplayName("Classifies IOException as network_error")
        void classifiesNetworkError() {
            assertThat(SpotifyMetricsInterceptor.classifyErrorCategory(0, null, new IOException("Connection reset")))
                    .isEqualTo("network_error");
        }
    }

    @Nested
    @DisplayName("Intercept Execution & Metrics Recording")
    class InterceptExecutionTests {

        @Test
        @DisplayName("Records request counter and latency timer on successful 200 response")
        void recordsSuccessfulRequest() throws IOException {
            HttpRequest request = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("https://api.spotify.com/v1/me"));
            byte[] body = new byte[0];

            ClientHttpRequestExecution execution = (req, b) -> new MockClientHttpResponse(new byte[0], HttpStatusCode.valueOf(200));

            ClientHttpResponse response = interceptor.intercept(request, body, execution);

            assertThat(response.getStatusCode().value()).isEqualTo(200);

            Counter requestCounter = registry.get(MetricNames.SPOTIFY_API_REQUESTS)
                    .tag(TagKeys.PROVIDER, "spotify")
                    .tag(TagKeys.OPERATION, "get_profile")
                    .tag(TagKeys.STATUS, "200")
                    .counter();
            assertThat(requestCounter.count()).isEqualTo(1.0);

            Timer timer = registry.get(MetricNames.SPOTIFY_API_LATENCY)
                    .tag(TagKeys.PROVIDER, "spotify")
                    .tag(TagKeys.OPERATION, "get_profile")
                    .tag(TagKeys.STATUS, "success")
                    .timer();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isGreaterThanOrEqualTo(0);

            assertThat(registry.find(MetricNames.SPOTIFY_API_ERRORS).counter()).isNull();
        }

        @Test
        @DisplayName("Records request, error counter, and error timer on HTTP 404 response")
        void records404Error() throws IOException {
            HttpRequest request = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("https://api.spotify.com/v1/playlists/p123"));
            byte[] body = new byte[0];

            ClientHttpRequestExecution execution = (req, b) -> new MockClientHttpResponse(new byte[0], HttpStatusCode.valueOf(404));

            ClientHttpResponse response = interceptor.intercept(request, body, execution);

            assertThat(response.getStatusCode().value()).isEqualTo(404);

            Counter requestCounter = registry.get(MetricNames.SPOTIFY_API_REQUESTS)
                    .tag(TagKeys.PROVIDER, "spotify")
                    .tag(TagKeys.OPERATION, "get_playlist")
                    .tag(TagKeys.STATUS, "404")
                    .counter();
            assertThat(requestCounter.count()).isEqualTo(1.0);

            Counter errorCounter = registry.get(MetricNames.SPOTIFY_API_ERRORS)
                    .tag(TagKeys.PROVIDER, "spotify")
                    .tag(TagKeys.OPERATION, "get_playlist")
                    .tag(TagKeys.STATUS, "404")
                    .tag(TagKeys.ERROR_CATEGORY, "client_error")
                    .counter();
            assertThat(errorCounter.count()).isEqualTo(1.0);

            Timer timer = registry.get(MetricNames.SPOTIFY_API_LATENCY)
                    .tag(TagKeys.STATUS, "error")
                    .timer();
            assertThat(timer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("Records rate limit error on HTTP 429 response")
        void records429RateLimit() throws IOException {
            HttpRequest request = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("https://api.spotify.com/v1/playlists/p123/tracks"));
            byte[] body = new byte[0];

            MockClientHttpResponse mockResponse = new MockClientHttpResponse(new byte[0], HttpStatusCode.valueOf(429));
            mockResponse.getHeaders().add(HttpHeaders.RETRY_AFTER, "5");
            ClientHttpRequestExecution execution = (req, b) -> mockResponse;

            interceptor.intercept(request, body, execution);

            Counter errorCounter = registry.get(MetricNames.SPOTIFY_API_ERRORS)
                    .tag(TagKeys.PROVIDER, "spotify")
                    .tag(TagKeys.OPERATION, "get_playlist_tracks")
                    .tag(TagKeys.STATUS, "429")
                    .tag(TagKeys.ERROR_CATEGORY, "rate_limit")
                    .counter();
            assertThat(errorCounter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Records network error and rethrows when transport throws IOException")
        void recordsNetworkException() {
            HttpRequest request = new MockClientHttpRequest(
                    HttpMethod.GET, URI.create("https://api.spotify.com/v1/me"));
            byte[] body = new byte[0];

            ClientHttpRequestExecution execution = (req, b) -> {
                throw new IOException("Connection refused");
            };

            assertThatThrownBy(() -> interceptor.intercept(request, body, execution))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Connection refused");

            Counter requestCounter = registry.get(MetricNames.SPOTIFY_API_REQUESTS)
                    .tag(TagKeys.STATUS, "error")
                    .counter();
            assertThat(requestCounter.count()).isEqualTo(1.0);

            Counter errorCounter = registry.get(MetricNames.SPOTIFY_API_ERRORS)
                    .tag(TagKeys.ERROR_CATEGORY, "network_error")
                    .tag(TagKeys.STATUS, "error")
                    .counter();
            assertThat(errorCounter.count()).isEqualTo(1.0);

            Timer timer = registry.get(MetricNames.SPOTIFY_API_LATENCY)
                    .tag(TagKeys.STATUS, "error")
                    .timer();
            assertThat(timer.count()).isEqualTo(1);
        }
    }
}
