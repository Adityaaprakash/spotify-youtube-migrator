package com.spotifyyoutube.migrator.youtube.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@RestClientTest(YouTubeClient.class)
@Import(YouTubeProperties.class)
@DisplayName("Phase 11F: YouTubeClient Playlist Item Removal (Rollback) Test")
class YouTubeClientRollbackTest {

    @Autowired
    private YouTubeClient youTubeClient;

    @Autowired
    private YouTubeProperties properties;

    @Autowired
    private MockRestServiceServer mockServer;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String ACCESS_TOKEN = "ya29.test_rollback_access_token";
    private static final String PLAYLIST_ITEM_ID = "UExUZXN0SXRlbUlkMTIz";

    @BeforeEach
    void setUp() {
        mockServer.reset();
        properties.setApiKey("test-api-key");
        properties.setBaseUrl("https://www.googleapis.com/youtube/v3");
    }

    @Nested
    @DisplayName("Successful Playlist Item Removal")
    class SuccessfulRemoval {

        @Test
        @DisplayName("204 No Content: Deletes playlist item using YouTube playlistItems?id endpoint")
        void removesPlaylistItemSuccessfully() {
            mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?id=" + PLAYLIST_ITEM_ID))
                    .andExpect(method(HttpMethod.DELETE))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                    .andRespond(withStatus(HttpStatus.NO_CONTENT));

            youTubeClient.removeVideoFromPlaylist(ACCESS_TOKEN, PLAYLIST_ITEM_ID);
            mockServer.verify();
        }

        @Test
        @DisplayName("404 Not Found: Throws ResourceNotFoundException (handled upstream as already deleted)")
        void treats404AsResourceNotFound() {
            mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?id=" + PLAYLIST_ITEM_ID))
                    .andExpect(method(HttpMethod.DELETE))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND));

            assertThatThrownBy(() -> youTubeClient.removeVideoFromPlaylist(ACCESS_TOKEN, PLAYLIST_ITEM_ID))
                    .isInstanceOf(com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException.class);
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("Error Handling & Failure Classification")
    class ErrorHandling {

        @Test
        @DisplayName("401 Unauthorized: Throws ExternalProviderException marked non-retryable")
        void handles401Unauthorized() {
            mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?id=" + PLAYLIST_ITEM_ID))
                    .andExpect(method(HttpMethod.DELETE))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

            assertThatThrownBy(() -> youTubeClient.removeVideoFromPlaylist(ACCESS_TOKEN, PLAYLIST_ITEM_ID))
                    .isInstanceOf(ExternalProviderException.class)
                    .satisfies(ex -> {
                        ExternalProviderException e = (ExternalProviderException) ex;
                        assertThat(e.isRetryable()).isFalse();
                    });
            mockServer.verify();
        }

        @Test
        @DisplayName("403 Forbidden: Throws ExternalProviderException marked non-retryable")
        void handles403Forbidden() {
            mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?id=" + PLAYLIST_ITEM_ID))
                    .andExpect(method(HttpMethod.DELETE))
                    .andRespond(withStatus(HttpStatus.FORBIDDEN));

            assertThatThrownBy(() -> youTubeClient.removeVideoFromPlaylist(ACCESS_TOKEN, PLAYLIST_ITEM_ID))
                    .isInstanceOf(ExternalProviderException.class)
                    .satisfies(ex -> {
                        ExternalProviderException e = (ExternalProviderException) ex;
                        assertThat(e.isRetryable()).isFalse();
                    });
            mockServer.verify();
        }

        @Test
        @DisplayName("429 Too Many Requests: Throws retryable ExternalProviderException")
        void handles429RateLimit() {
            mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?id=" + PLAYLIST_ITEM_ID))
                    .andExpect(method(HttpMethod.DELETE))
                    .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

            assertThatThrownBy(() -> youTubeClient.removeVideoFromPlaylist(ACCESS_TOKEN, PLAYLIST_ITEM_ID))
                    .isInstanceOf(ExternalProviderException.class)
                    .satisfies(ex -> {
                        ExternalProviderException e = (ExternalProviderException) ex;
                        assertThat(e.isRetryable()).isTrue();
                    });
            mockServer.verify();
        }

        @Test
        @DisplayName("503 Service Unavailable: Throws retryable ExternalProviderException")
        void handles503ServiceUnavailable() {
            mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?id=" + PLAYLIST_ITEM_ID))
                    .andExpect(method(HttpMethod.DELETE))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

            assertThatThrownBy(() -> youTubeClient.removeVideoFromPlaylist(ACCESS_TOKEN, PLAYLIST_ITEM_ID))
                    .isInstanceOf(ExternalProviderException.class)
                    .satisfies(ex -> {
                        ExternalProviderException e = (ExternalProviderException) ex;
                        assertThat(e.isRetryable()).isTrue();
                    });
            mockServer.verify();
        }

        @Test
        @DisplayName("Network Timeout: Throws retryable ExternalProviderException")
        void handlesNetworkTimeout() {
            mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?id=" + PLAYLIST_ITEM_ID))
                    .andExpect(method(HttpMethod.DELETE))
                    .andRespond(withException(new ResourceAccessException("Connection timeout", new SocketTimeoutException("Read timed out"))));

            assertThatThrownBy(() -> youTubeClient.removeVideoFromPlaylist(ACCESS_TOKEN, PLAYLIST_ITEM_ID))
                    .isInstanceOf(ExternalProviderException.class)
                    .satisfies(ex -> {
                        ExternalProviderException e = (ExternalProviderException) ex;
                        assertThat(e.isRetryable()).isTrue();
                    });
            mockServer.verify();
        }

        @Test
        @DisplayName("Validation: Throws IllegalArgumentException on blank or null parameters")
        void validatesInputParameters() {
            assertThatThrownBy(() -> youTubeClient.removeVideoFromPlaylist(null, PLAYLIST_ITEM_ID))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> youTubeClient.removeVideoFromPlaylist("", PLAYLIST_ITEM_ID))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> youTubeClient.removeVideoFromPlaylist(ACCESS_TOKEN, null))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> youTubeClient.removeVideoFromPlaylist(ACCESS_TOKEN, "   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
