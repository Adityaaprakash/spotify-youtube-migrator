package com.spotifyyoutube.migrator.youtube.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePageInfoDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistItemDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistItemSnippetResponseDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistInsertRequestDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistListResponseDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistSnippetDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubePlaylistStatusDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeResourceIdDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeSearchListResponseDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeSearchResultDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeSearchSnippetDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeThumbnailDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoContentDetailsDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoItemDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoListResponseDto;
import com.spotifyyoutube.migrator.youtube.infrastructure.dto.YouTubeVideoSnippetDto;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@RestClientTest(YouTubeClient.class)
@Import(YouTubeProperties.class)
class YouTubeClientTest {

    @Autowired
    private YouTubeClient youTubeClient;

    @Autowired
    private YouTubeProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        mockServer.reset();
        properties.setApiKey("test-api-key");
        properties.setBaseUrl("https://www.googleapis.com/youtube/v3");
    }

    @Test
    void searchVideos_shouldBuildCorrectRequestAndReturnResults_onSuccess() throws Exception {
        YouTubeThumbnailDto thumbnail = new YouTubeThumbnailDto("https://i.ytimg.com/vi/abc/hqdefault.jpg", 480, 360);
        YouTubeSearchSnippetDto snippet = new YouTubeSearchSnippetDto(
                "2023-01-01T00:00:00Z",
                "c123",
                "Bohemian Rhapsody",
                "Official Queen video",
                Map.of("high", thumbnail),
                "Queen Official",
                "none",
                "2023-01-01T00:00:00Z"
        );
        YouTubeResourceIdDto id = new YouTubeResourceIdDto("youtube#video", "vid123", null, null);
        YouTubeSearchResultDto item = new YouTubeSearchResultDto("youtube#searchResult", "etag1", id, snippet);
        YouTubeSearchListResponseDto mockResponse = new YouTubeSearchListResponseDto(
                "youtube#searchListResponse",
                "etagRoot",
                "nextPageToken123",
                null,
                "US",
                new YouTubePageInfoDto(100, 25),
                List.of(item)
        );

        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&q=Queen&maxResults=25&key=test-api-key"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(mockResponse), MediaType.APPLICATION_JSON));

        YouTubeSearchListResponseDto result = youTubeClient.searchVideos("Queen", 25, null);

        assertThat(result).isNotNull();
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id().videoId()).isEqualTo("vid123");
        assertThat(result.items().get(0).snippet().title()).isEqualTo("Bohemian Rhapsody");
        assertThat(result.nextPageToken()).isEqualTo("nextPageToken123");
        mockServer.verify();
    }

    @Test
    void searchVideos_shouldProperlyEncodeQueryParams() throws Exception {
        YouTubeSearchListResponseDto emptyResponse = new YouTubeSearchListResponseDto(
                "youtube#searchListResponse", "etag", null, null, null, new YouTubePageInfoDto(0, 10), List.of()
        );

        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&q=rock%20%26%20roll&maxResults=10&key=test-api-key"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(emptyResponse), MediaType.APPLICATION_JSON));

        YouTubeSearchListResponseDto result = youTubeClient.searchVideos("rock & roll", 10, null);

        assertThat(result).isNotNull();
        assertThat(result.items()).isEmpty();
        mockServer.verify();
    }

    @Test
    void searchVideos_shouldIncludePageToken_whenProvided() throws Exception {
        YouTubeSearchListResponseDto emptyResponse = new YouTubeSearchListResponseDto(
                "youtube#searchListResponse", "etag", null, null, null, new YouTubePageInfoDto(0, 10), List.of()
        );

        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&q=test&maxResults=10&key=test-api-key&pageToken=CAUQAA"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(emptyResponse), MediaType.APPLICATION_JSON));

        YouTubeSearchListResponseDto result = youTubeClient.searchVideos("test", 10, "CAUQAA");

        assertThat(result).isNotNull();
        mockServer.verify();
    }

    @Test
    void searchVideos_shouldThrowIllegalArgumentException_whenQueryIsBlank() {
        assertThatThrownBy(() -> youTubeClient.searchVideos("", 10, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Search query must not be blank");
    }

    @Test
    void searchVideos_shouldThrowInvalidStateException_whenApiKeyIsMissing() {
        properties.setApiKey("");

        assertThatThrownBy(() -> youTubeClient.searchVideos("test", 10, null))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("YouTube API key is not configured");
    }

    @Test
    void searchVideos_shouldThrowExternalProviderException_on401Unauthorized() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&q=test&maxResults=10&key=test-api-key"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> youTubeClient.searchVideos("test", 10, null))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(401);
                    assertThat(ex.getMessage()).contains("invalid API key");
                });
    }

    @Test
    void searchVideos_shouldThrowExternalProviderException_on403QuotaExceeded() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&q=test&maxResults=10&key=test-api-key"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> youTubeClient.searchVideos("test", 10, null))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(403);
                    assertThat(ex.getMessage()).contains("quota exceeded");
                });
    }

    @Test
    void searchVideos_shouldThrowExternalProviderException_on429RateLimitWithRetryAfter() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "60");

        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&q=test&maxResults=10&key=test-api-key"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));

        assertThatThrownBy(() -> youTubeClient.searchVideos("test", 10, null))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(429);
                    assertThat(ex.getRetryAfter()).isEqualTo("60");
                });
    }

    @Test
    void searchVideos_shouldThrowExternalProviderException_on400BadRequest() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&q=test&maxResults=10&key=test-api-key"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> youTubeClient.searchVideos("test", 10, null))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("request invalid");
                });
    }

    @Test
    void searchVideos_shouldThrowExternalProviderException_on500ServerError() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&q=test&maxResults=10&key=test-api-key"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> youTubeClient.searchVideos("test", 10, null))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(500);
                });
    }

    @Test
    void searchVideos_shouldThrowResourceNotFound_on404() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&q=test&maxResults=10&key=test-api-key"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> youTubeClient.searchVideos("test", 10, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void searchVideos_shouldThrowResourceAccessException_onTimeout() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&q=test&maxResults=10&key=test-api-key"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> youTubeClient.searchVideos("test", 10, null))
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    void searchVideos_shouldThrowRestClientException_onMalformedJson() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&q=test&maxResults=10&key=test-api-key"))
                .andRespond(withSuccess("not-valid-json{", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> youTubeClient.searchVideos("test", 10, null))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    void getVideoMetadata_shouldBuildCorrectRequestAndReturnMetadata_onSuccess() throws Exception {
        YouTubeThumbnailDto thumb = new YouTubeThumbnailDto("https://i.ytimg.com/vi/v123/hqdefault.jpg", 480, 360);
        YouTubeVideoSnippetDto snippet = new YouTubeVideoSnippetDto(
                "2023-01-01T00:00:00Z",
                "c123",
                "Song Title",
                "Song Description",
                Map.of("high", thumb),
                "Artist Channel"
        );
        YouTubeVideoContentDetailsDto contentDetails = new YouTubeVideoContentDetailsDto("PT3M45S");
        YouTubeVideoItemDto item = new YouTubeVideoItemDto("youtube#video", "etag1", "v123", snippet, contentDetails);
        YouTubeVideoListResponseDto responseDto = new YouTubeVideoListResponseDto(
                "youtube#videoListResponse",
                "etagRoot",
                new YouTubePageInfoDto(1, 1),
                List.of(item)
        );

        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&id=v123&key=test-api-key"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(responseDto), MediaType.APPLICATION_JSON));

        YouTubeVideoListResponseDto result = youTubeClient.getVideoMetadata("v123");

        assertThat(result).isNotNull();
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo("v123");
        assertThat(result.items().get(0).snippet().title()).isEqualTo("Song Title");
        assertThat(result.items().get(0).contentDetails().duration()).isEqualTo("PT3M45S");
        mockServer.verify();
    }

    @Test
    void getVideoMetadata_shouldThrowIllegalArgumentException_whenVideoIdIsBlank() {
        assertThatThrownBy(() -> youTubeClient.getVideoMetadata("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Video ID must not be blank");
    }

    @Test
    void getVideoMetadata_shouldThrowInvalidStateException_whenApiKeyMissing() {
        properties.setApiKey("");

        assertThatThrownBy(() -> youTubeClient.getVideoMetadata("v123"))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("YouTube API key is not configured");
    }

    @Test
    void getVideoMetadata_shouldThrowExternalProviderException_on401Unauthorized() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&id=v123&key=test-api-key"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> youTubeClient.getVideoMetadata("v123"))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(401);
                });
    }

    @Test
    void getVideoMetadata_shouldThrowExternalProviderException_on403QuotaExceeded() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&id=v123&key=test-api-key"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> youTubeClient.getVideoMetadata("v123"))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(403);
                });
    }

    @Test
    void getVideoMetadata_shouldThrowExternalProviderException_on429RateLimit() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "30");

        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&id=v123&key=test-api-key"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));

        assertThatThrownBy(() -> youTubeClient.getVideoMetadata("v123"))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(429);
                    assertThat(ex.getRetryAfter()).isEqualTo("30");
                });
    }

    @Test
    void getVideoMetadata_shouldThrowExternalProviderException_on400BadRequest() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&id=v123&key=test-api-key"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> youTubeClient.getVideoMetadata("v123"))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(400);
                });
    }

    @Test
    void getVideoMetadata_shouldThrowResourceNotFound_on404() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&id=v123&key=test-api-key"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> youTubeClient.getVideoMetadata("v123"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getVideoMetadata_shouldThrowExternalProviderException_on500ServerError() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&id=v123&key=test-api-key"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> youTubeClient.getVideoMetadata("v123"))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(500);
                });
    }

    @Test
    void createPlaylist_shouldSendBearerTokenAndJsonBody_onSuccess() throws Exception {
        YouTubePlaylistInsertRequestDto requestDto = new YouTubePlaylistInsertRequestDto(
                new YouTubePlaylistSnippetDto("My New Playlist", "Description", null, null, null, null),
                new YouTubePlaylistStatusDto("private")
        );
        YouTubePlaylistDto mockResponse = new YouTubePlaylistDto(
                "youtube#playlist",
                "etag1",
                "PL123",
                new YouTubePlaylistSnippetDto("My New Playlist", "Description", "2023-01-01T00:00:00Z", "chan1", "Channel", null),
                new YouTubePlaylistStatusDto("private")
        );

        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlists?part=snippet,status"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer mock-access-token"))
                .andRespond(withSuccess(objectMapper.writeValueAsString(mockResponse), MediaType.APPLICATION_JSON));

        YouTubePlaylistDto result = youTubeClient.createPlaylist("mock-access-token", requestDto);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo("PL123");
        assertThat(result.snippet().title()).isEqualTo("My New Playlist");
        mockServer.verify();
    }

    @Test
    void createPlaylist_shouldThrowIllegalArgumentException_whenAccessTokenIsBlank() {
        YouTubePlaylistInsertRequestDto requestDto = new YouTubePlaylistInsertRequestDto(null, null);

        assertThatThrownBy(() -> youTubeClient.createPlaylist("", requestDto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Access token must not be blank");
    }

    @Test
    void createPlaylist_shouldThrowIllegalArgumentException_whenRequestBodyIsNull() {
        assertThatThrownBy(() -> youTubeClient.createPlaylist("token", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Playlist request body must not be null");
    }

    @Test
    void createPlaylist_shouldThrowExternalProviderException_on401Unauthorized() {
        YouTubePlaylistInsertRequestDto requestDto = new YouTubePlaylistInsertRequestDto(null, null);

        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlists?part=snippet,status"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> youTubeClient.createPlaylist("token", requestDto))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(401);
                });
    }

    @Test
    void createPlaylist_shouldThrowExternalProviderException_on403Forbidden() {
        YouTubePlaylistInsertRequestDto requestDto = new YouTubePlaylistInsertRequestDto(null, null);

        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlists?part=snippet,status"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> youTubeClient.createPlaylist("token", requestDto))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(403);
                });
    }

    @Test
    void createPlaylist_shouldThrowResourceNotFound_on404() {
        YouTubePlaylistInsertRequestDto requestDto = new YouTubePlaylistInsertRequestDto(null, null);

        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlists?part=snippet,status"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> youTubeClient.createPlaylist("token", requestDto))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void addVideoToPlaylist_shouldSendBearerTokenAndJsonBody_onSuccess() throws Exception {
        YouTubePlaylistItemDto mockResponse = new YouTubePlaylistItemDto(
                "youtube#playlistItem",
                "etag1",
                "pli999",
                new YouTubePlaylistItemSnippetResponseDto(
                        "PL123",
                        0,
                        new YouTubeResourceIdDto("youtube#video", "vid123", null, null),
                        "Video Title",
                        "Desc",
                        null
                )
        );

        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer mock-access-token"))
                .andRespond(withSuccess(objectMapper.writeValueAsString(mockResponse), MediaType.APPLICATION_JSON));

        YouTubePlaylistItemDto result = youTubeClient.addVideoToPlaylist("mock-access-token", "PL123", "vid123", 0);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo("pli999");
        assertThat(result.snippet().playlistId()).isEqualTo("PL123");
        assertThat(result.snippet().resourceId().videoId()).isEqualTo("vid123");
        mockServer.verify();
    }

    @Test
    void addVideoToPlaylist_shouldThrowIllegalArgumentException_whenParamsAreBlank() {
        assertThatThrownBy(() -> youTubeClient.addVideoToPlaylist("", "PL123", "vid123", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Access token must not be blank");

        assertThatThrownBy(() -> youTubeClient.addVideoToPlaylist("token", "", "vid123", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Playlist ID must not be blank");

        assertThatThrownBy(() -> youTubeClient.addVideoToPlaylist("token", "PL123", "", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Video ID must not be blank");
    }

    @Test
    void addVideoToPlaylist_shouldThrowResourceNotFound_on404() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> youTubeClient.addVideoToPlaylist("token", "PL123", "vid123", null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void addVideoToPlaylist_shouldThrowExternalProviderException_on403Forbidden() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> youTubeClient.addVideoToPlaylist("token", "PL123", "vid123", null))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(403);
                });
    }

    @Test
    void addVideoToPlaylist_shouldThrowExternalProviderException_on500ServerError() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThatThrownBy(() -> youTubeClient.addVideoToPlaylist("token", "PL123", "vid123", null))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(500);
                });
    }

    @Test
    void getPlaylist_shouldReturnPlaylistListResponse_whenSuccessful() throws Exception {
        YouTubePlaylistSnippetDto snippet = new YouTubePlaylistSnippetDto(
                "My Target Playlist",
                "Playlist Description",
                "2026-10-10T00:00:00Z",
                null,
                "My Channel",
                null
        );
        YouTubePlaylistStatusDto status = new YouTubePlaylistStatusDto("private");
        YouTubePlaylistDto item = new YouTubePlaylistDto("youtube#playlist", "etag1", "PLtarget", snippet, status);
        YouTubePlaylistListResponseDto mockResponse = new YouTubePlaylistListResponseDto(
                "youtube#playlistListResponse",
                "etag2",
                List.of(item),
                new YouTubePageInfoDto(1, 1)
        );

        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlists?part=snippet,status&id=PLtarget"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer google-token-123"))
                .andRespond(withSuccess(objectMapper.writeValueAsString(mockResponse), MediaType.APPLICATION_JSON));

        YouTubePlaylistListResponseDto result = youTubeClient.getPlaylist("google-token-123", "PLtarget");

        assertThat(result).isNotNull();
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo("PLtarget");
        assertThat(result.items().get(0).snippet().title()).isEqualTo("My Target Playlist");
        mockServer.verify();
    }

    @Test
    void getPlaylist_shouldThrowIllegalArgumentException_whenParamsAreBlank() {
        assertThatThrownBy(() -> youTubeClient.getPlaylist("", "PLtarget"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Access token must not be blank");

        assertThatThrownBy(() -> youTubeClient.getPlaylist("token", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Playlist ID must not be blank");
    }

    @Test
    void getPlaylist_shouldThrowResourceNotFound_on404() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlists?part=snippet,status&id=PLnotfound"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> youTubeClient.getPlaylist("token", "PLnotfound"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void getPlaylist_shouldThrowExternalProviderException_on401Unauthorized() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlists?part=snippet,status&id=PL123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> youTubeClient.getPlaylist("token", "PL123"))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(401);
                });
    }

    @Test
    void getPlaylist_shouldThrowExternalProviderException_on403Forbidden() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlists?part=snippet,status&id=PL123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> youTubeClient.getPlaylist("token", "PL123"))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(403);
                });
    }

    @Test
    void getPlaylist_shouldThrowExternalProviderException_on429RateLimit() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlists?part=snippet,status&id=PL123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "60"));

        assertThatThrownBy(() -> youTubeClient.getPlaylist("token", "PL123"))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(429);
                    assertThat(ex.getRetryAfter()).isEqualTo("60");
                });
    }

    @Test
    void getPlaylist_shouldThrowExternalProviderException_on500ServerError() {
        mockServer.expect(requestTo("https://www.googleapis.com/youtube/v3/playlists?part=snippet,status&id=PL123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertThatThrownBy(() -> youTubeClient.getPlaylist("token", "PL123"))
                .isInstanceOf(ExternalProviderException.class)
                .satisfies(e -> {
                    ExternalProviderException ex = (ExternalProviderException) e;
                    assertThat(ex.getProviderStatusCode()).isEqualTo(500);
                });
    }
}
