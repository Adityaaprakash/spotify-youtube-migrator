package com.spotifyyoutube.migrator.youtube.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePageInfoDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistItemDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistItemListResponseDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistItemSnippetResponseDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeResourceIdDto;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@RestClientTest(YouTubeClient.class)
@Import(YouTubeProperties.class)
@DisplayName("Phase 11G: YouTubeClient Playlist Items Inspection Test")
class YouTubeClientInspectionTest {

    @Autowired
    private YouTubeClient youTubeClient;

    @Autowired
    private YouTubeProperties properties;

    @Autowired
    private MockRestServiceServer mockServer;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String ACCESS_TOKEN = "ya29.test_inspection_access_token";
    private static final String PLAYLIST_ID = "PL_test_playlist_123";

    @BeforeEach
    void setUp() {
        mockServer.reset();
        properties.setApiKey("test-api-key");
        properties.setBaseUrl("https://www.googleapis.com/youtube/v3");
    }

    @Nested
    @DisplayName("Successful Playlist Item Retrieval")
    class SuccessfulRetrieval {

        @Test
        @DisplayName("200 OK: Single page playlist inspection returns items without next page token")
        void singlePageInspectionSuccess() throws Exception {
            YouTubePlaylistItemDto item1 = new YouTubePlaylistItemDto(
                    "youtube#playlistItem",
                    "etag1",
                    "item_1",
                    new YouTubePlaylistItemSnippetResponseDto(
                            PLAYLIST_ID,
                            0,
                            new YouTubeResourceIdDto("youtube#video", "vid_1", null, null),
                            "Song One",
                            "Description",
                            Collections.emptyMap(),
                            "2026-11-02T04:15:30.000Z"
                    )
            );
            YouTubePlaylistItemListResponseDto responseDto = new YouTubePlaylistItemListResponseDto(
                    "youtube#playlistItemListResponse",
                    "etag",
                    null,
                    null,
                    new YouTubePageInfoDto(1, 50),
                    List.of(item1)
            );

            mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&playlistId=" + PLAYLIST_ID + "&maxResults=50"))
                    .andExpect(method(HttpMethod.GET))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                    .andRespond(withStatus(HttpStatus.OK)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(objectMapper.writeValueAsString(responseDto)));

            YouTubePlaylistItemListResponseDto result = youTubeClient.getPlaylistItems(ACCESS_TOKEN, PLAYLIST_ID, 50, null);

            assertThat(result).isNotNull();
            assertThat(result.items()).hasSize(1);
            assertThat(result.items().get(0).id()).isEqualTo("item_1");
            assertThat(result.items().get(0).snippet().resourceId().videoId()).isEqualTo("vid_1");
            assertThat(result.items().get(0).snippet().publishedAt()).isEqualTo("2026-11-02T04:15:30.000Z");
            assertThat(result.nextPageToken()).isNull();
            mockServer.verify();
        }

        @Test
        @DisplayName("200 OK: Paginated request includes pageToken query parameter")
        void paginatedRequestIncludesPageToken() throws Exception {
            YouTubePlaylistItemListResponseDto responseDto = new YouTubePlaylistItemListResponseDto(
                    "youtube#playlistItemListResponse",
                    "etag",
                    "page3_token",
                    "page1_token",
                    new YouTubePageInfoDto(100, 50),
                    Collections.emptyList()
            );

            mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&playlistId=" + PLAYLIST_ID + "&maxResults=50&pageToken=page2_token"))
                    .andExpect(method(HttpMethod.GET))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                    .andRespond(withStatus(HttpStatus.OK)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(objectMapper.writeValueAsString(responseDto)));

            YouTubePlaylistItemListResponseDto result = youTubeClient.getPlaylistItems(ACCESS_TOKEN, PLAYLIST_ID, 50, "page2_token");

            assertThat(result).isNotNull();
            assertThat(result.nextPageToken()).isEqualTo("page3_token");
            mockServer.verify();
        }

        @Test
        @DisplayName("200 OK: Empty playlist returns 0 items")
        void emptyPlaylistReturnsZeroItems() throws Exception {
            YouTubePlaylistItemListResponseDto responseDto = new YouTubePlaylistItemListResponseDto(
                    "youtube#playlistItemListResponse",
                    "etag",
                    null,
                    null,
                    new YouTubePageInfoDto(0, 50),
                    Collections.emptyList()
            );

            mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&playlistId=" + PLAYLIST_ID + "&maxResults=50"))
                    .andExpect(method(HttpMethod.GET))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                    .andRespond(withStatus(HttpStatus.OK)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(objectMapper.writeValueAsString(responseDto)));

            YouTubePlaylistItemListResponseDto result = youTubeClient.getPlaylistItems(ACCESS_TOKEN, PLAYLIST_ID, 50, null);

            assertThat(result).isNotNull();
            assertThat(result.items()).isEmpty();
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("Error Handling & Status Codes")
    class ErrorHandling {

        @Test
        @DisplayName("404 Not Found: Throws ResourceNotFoundException")
        void handles404NotFound() {
            mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&playlistId=" + PLAYLIST_ID + "&maxResults=50"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND));

            assertThatThrownBy(() -> youTubeClient.getPlaylistItems(ACCESS_TOKEN, PLAYLIST_ID, 50, null))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("not found");
            mockServer.verify();
        }

        @Test
        @DisplayName("401 Unauthorized: Throws ExternalProviderException with status 401")
        void handles401Unauthorized() {
            mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&playlistId=" + PLAYLIST_ID + "&maxResults=50"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

            assertThatThrownBy(() -> youTubeClient.getPlaylistItems(ACCESS_TOKEN, PLAYLIST_ID, 50, null))
                    .isInstanceOf(ExternalProviderException.class)
                    .satisfies(ex -> {
                        ExternalProviderException e = (ExternalProviderException) ex;
                        assertThat(e.getProviderStatusCode()).isEqualTo(401);
                    });
            mockServer.verify();
        }

        @Test
        @DisplayName("403 Forbidden: Throws ExternalProviderException with status 403")
        void handles403Forbidden() {
            mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&playlistId=" + PLAYLIST_ID + "&maxResults=50"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.FORBIDDEN));

            assertThatThrownBy(() -> youTubeClient.getPlaylistItems(ACCESS_TOKEN, PLAYLIST_ID, 50, null))
                    .isInstanceOf(ExternalProviderException.class)
                    .satisfies(ex -> {
                        ExternalProviderException e = (ExternalProviderException) ex;
                        assertThat(e.getProviderStatusCode()).isEqualTo(403);
                    });
            mockServer.verify();
        }

        @Test
        @DisplayName("429 Too Many Requests: Throws ExternalProviderException with status 429")
        void handles429RateLimit() {
            mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&playlistId=" + PLAYLIST_ID + "&maxResults=50"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                            .header(HttpHeaders.RETRY_AFTER, "60"));

            assertThatThrownBy(() -> youTubeClient.getPlaylistItems(ACCESS_TOKEN, PLAYLIST_ID, 50, null))
                    .isInstanceOf(ExternalProviderException.class)
                    .satisfies(ex -> {
                        ExternalProviderException e = (ExternalProviderException) ex;
                        assertThat(e.getProviderStatusCode()).isEqualTo(429);
                        assertThat(e.getRetryAfterSeconds()).isEqualTo(60L);
                    });
            mockServer.verify();
        }

        @Test
        @DisplayName("500 / 503 Server Error: Throws ExternalProviderException")
        void handles500ServerError() {
            mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&playlistId=" + PLAYLIST_ID + "&maxResults=50"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

            assertThatThrownBy(() -> youTubeClient.getPlaylistItems(ACCESS_TOKEN, PLAYLIST_ID, 50, null))
                    .isInstanceOf(ExternalProviderException.class)
                    .satisfies(ex -> {
                        ExternalProviderException e = (ExternalProviderException) ex;
                        assertThat(e.getProviderStatusCode()).isEqualTo(500);
                    });
            mockServer.verify();
        }

        @Test
        @DisplayName("Network timeout: Throws ResourceAccessException")
        void handlesNetworkTimeout() {
            mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&playlistId=" + PLAYLIST_ID + "&maxResults=50"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withException(new ResourceAccessException("Read timed out", new SocketTimeoutException())));

            assertThatThrownBy(() -> youTubeClient.getPlaylistItems(ACCESS_TOKEN, PLAYLIST_ID, 50, null))
                    .isInstanceOf(ResourceAccessException.class);
            mockServer.verify();
        }

        @Test
        @DisplayName("Input validation: Null or blank parameters throw IllegalArgumentException")
        void validatesInputs() {
            assertThatThrownBy(() -> youTubeClient.getPlaylistItems(null, PLAYLIST_ID, 50, null))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> youTubeClient.getPlaylistItems(ACCESS_TOKEN, null, 50, null))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> youTubeClient.getPlaylistItems(ACCESS_TOKEN, "   ", 50, null))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> youTubeClient.getPlaylistItems(ACCESS_TOKEN, PLAYLIST_ID, 0, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
