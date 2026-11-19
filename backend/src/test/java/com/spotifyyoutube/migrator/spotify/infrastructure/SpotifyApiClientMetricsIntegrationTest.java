package com.spotifyyoutube.migrator.spotify.infrastructure;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.metrics.MetricNames;
import com.spotifyyoutube.migrator.common.metrics.TagKeys;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPagingDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPlaylistSummaryDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyPlaylistTrackDto;
import com.spotifyyoutube.migrator.spotify.infrastructure.dto.SpotifyUserProfileDto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("SpotifyApiClient Metrics Integration Tests")
class SpotifyApiClientMetricsIntegrationTest {

    private SimpleMeterRegistry registry;
    private SpotifyApiClient spotifyApiClient;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        SpotifyMetricsInterceptor interceptor = new SpotifyMetricsInterceptor(registry);

        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        spotifyApiClient = new SpotifyApiClient(builder, interceptor);
    }

    @Test
    @DisplayName("Single profile request increments spotify.api.requests counter and records latency timer")
    void recordsProfileMetrics() {
        String mockProfileJson = """
        {
            "id": "u100",
            "display_name": "Sample User",
            "email": "sample@example.com"
        }
        """;

        mockServer.expect(requestTo("https://api.spotify.com/v1/me"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-1"))
                .andRespond(withSuccess(mockProfileJson, MediaType.APPLICATION_JSON));

        SpotifyUserProfileDto profile = spotifyApiClient.getCurrentUserProfile("token-1");

        assertThat(profile).isNotNull();
        assertThat(profile.id()).isEqualTo("u100");
        mockServer.verify();

        Counter reqCounter = registry.get(MetricNames.SPOTIFY_API_REQUESTS)
                .tag(TagKeys.PROVIDER, "spotify")
                .tag(TagKeys.OPERATION, "get_profile")
                .tag(TagKeys.STATUS, "200")
                .counter();
        assertThat(reqCounter.count()).isEqualTo(1.0);

        Timer timer = registry.get(MetricNames.SPOTIFY_API_LATENCY)
                .tag(TagKeys.PROVIDER, "spotify")
                .tag(TagKeys.OPERATION, "get_profile")
                .tag(TagKeys.STATUS, "success")
                .timer();
        assertThat(timer.count()).isEqualTo(1);

        assertThat(registry.find(MetricNames.SPOTIFY_API_ERRORS).counter()).isNull();
    }

    @Test
    @DisplayName("Pagination across multiple pages records one metric increment per outbound HTTP attempt")
    void recordsPaginationMetricsPerAttempt() {
        String page1Response = """
        {
            "href": "https://api.spotify.com/v1/playlists/p1/tracks?limit=50&offset=0",
            "items": [],
            "limit": 50,
            "offset": 0,
            "total": 100
        }
        """;
        String page2Response = """
        {
            "href": "https://api.spotify.com/v1/playlists/p1/tracks?limit=50&offset=50",
            "items": [],
            "limit": 50,
            "offset": 50,
            "total": 100
        }
        """;

        mockServer.expect(requestTo("https://api.spotify.com/v1/playlists/p1/tracks?limit=50&offset=0"))
                .andRespond(withSuccess(page1Response, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("https://api.spotify.com/v1/playlists/p1/tracks?limit=50&offset=50"))
                .andRespond(withSuccess(page2Response, MediaType.APPLICATION_JSON));

        SpotifyPagingDto<SpotifyPlaylistTrackDto> p1 = spotifyApiClient.getPlaylistTracks("token-1", "p1", 50, 0);
        SpotifyPagingDto<SpotifyPlaylistTrackDto> p2 = spotifyApiClient.getPlaylistTracks("token-1", "p1", 50, 50);

        assertThat(p1).isNotNull();
        assertThat(p2).isNotNull();
        mockServer.verify();

        Counter reqCounter = registry.get(MetricNames.SPOTIFY_API_REQUESTS)
                .tag(TagKeys.PROVIDER, "spotify")
                .tag(TagKeys.OPERATION, "get_playlist_tracks")
                .counter();
        assertThat(reqCounter.count()).isEqualTo(2.0);

        Timer timer = registry.get(MetricNames.SPOTIFY_API_LATENCY)
                .tag(TagKeys.PROVIDER, "spotify")
                .tag(TagKeys.OPERATION, "get_playlist_tracks")
                .timer();
        assertThat(timer.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Provider server error increments both request and error counters with server_error category")
    void recordsServerErrorMetrics() {
        mockServer.expect(requestTo("https://api.spotify.com/v1/me"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> spotifyApiClient.getCurrentUserProfile("token-err"))
                .isInstanceOf(ExternalProviderException.class);
        mockServer.verify();

        Counter reqCounter = registry.get(MetricNames.SPOTIFY_API_REQUESTS)
                .tag(TagKeys.PROVIDER, "spotify")
                .tag(TagKeys.STATUS, "500")
                .counter();
        assertThat(reqCounter.count()).isEqualTo(1.0);

        Counter errCounter = registry.get(MetricNames.SPOTIFY_API_ERRORS)
                .tag(TagKeys.PROVIDER, "spotify")
                .tag(TagKeys.ERROR_CATEGORY, "server_error")
                .tag(TagKeys.STATUS, "500")
                .counter();
        assertThat(errCounter.count()).isEqualTo(1.0);

        Timer timer = registry.get(MetricNames.SPOTIFY_API_LATENCY)
                .tag(TagKeys.STATUS, "error")
                .timer();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Empty successful response does not increment error counter")
    void emptySuccessfulResponseDoesNotIncrementErrors() {
        String emptyPlaylists = """
        {
            "href": "https://api.spotify.com/v1/me/playlists",
            "items": [],
            "limit": 50,
            "offset": 0,
            "total": 0
        }
        """;

        mockServer.expect(requestTo("https://api.spotify.com/v1/me/playlists?limit=50&offset=0"))
                .andRespond(withSuccess(emptyPlaylists, MediaType.APPLICATION_JSON));

        SpotifyPagingDto<SpotifyPlaylistSummaryDto> result = spotifyApiClient.getUserPlaylists("token-1", 50, 0);

        assertThat(result).isNotNull();
        assertThat(result.total()).isEqualTo(0);
        mockServer.verify();

        Counter reqCounter = registry.get(MetricNames.SPOTIFY_API_REQUESTS)
                .tag(TagKeys.OPERATION, "get_playlists")
                .counter();
        assertThat(reqCounter.count()).isEqualTo(1.0);

        assertThat(registry.find(MetricNames.SPOTIFY_API_ERRORS).counter()).isNull();
    }
}
