package com.spotifyyoutube.migrator.youtube.api;

import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSearchResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSummaryResponse;
import com.spotifyyoutube.migrator.youtube.application.YouTubeSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(YouTubeController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class YouTubeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private YouTubeSearchService searchService;

    @Test
    @WithMockUser(username = "user@example.com")
    void searchVideos_shouldReturnResults_whenAuthenticatedAndValidRequest() throws Exception {
        YouTubeVideoSummaryResponse video = new YouTubeVideoSummaryResponse(
                "v123", "Bohemian Rhapsody", "Queen", "Description", "https://img.youtube.com/thumb.jpg", "2023-01-01T00:00:00Z"
        );
        YouTubeVideoSearchResponse response = new YouTubeVideoSearchResponse(
                List.of(video), "nextToken", null, 100, 25
        );

        when(searchService.searchVideos("Queen", null, null)).thenReturn(response);

        mockMvc.perform(get("/api/youtube/videos/search?q=Queen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].videoId").value("v123"))
                .andExpect(jsonPath("$.items[0].title").value("Bohemian Rhapsody"))
                .andExpect(jsonPath("$.items[0].channelTitle").value("Queen"))
                .andExpect(jsonPath("$.items[0].thumbnailUrl").value("https://img.youtube.com/thumb.jpg"))
                .andExpect(jsonPath("$.nextPageToken").value("nextToken"))
                .andExpect(jsonPath("$.totalResults").value(100))
                .andExpect(jsonPath("$.resultsPerPage").value(25));
    }

    @Test
    @WithMockUser(username = "user@example.com")
    void searchVideos_shouldPassPaginationParams_whenProvided() throws Exception {
        YouTubeVideoSearchResponse response = new YouTubeVideoSearchResponse(
                List.of(), "nextToken2", "prevToken1", 50, 10
        );

        when(searchService.searchVideos("Rock", "pageToken1", 10)).thenReturn(response);

        mockMvc.perform(get("/api/youtube/videos/search?q=Rock&pageToken=pageToken1&maxResults=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResults").value(50))
                .andExpect(jsonPath("$.resultsPerPage").value(10));
    }

    @Test
    void searchVideos_shouldReturn403_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/youtube/videos/search?q=Queen"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "user@example.com")
    void searchVideos_shouldReturn400_whenQueryParamMissing() throws Exception {
        mockMvc.perform(get("/api/youtube/videos/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @WithMockUser(username = "user@example.com")
    void searchVideos_shouldReturn400_whenServiceThrowsIllegalArgumentException() throws Exception {
        when(searchService.searchVideos("", null, null))
                .thenThrow(new IllegalArgumentException("Search query must not be blank"));

        mockMvc.perform(get("/api/youtube/videos/search?q="))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Search query must not be blank"));
    }

    @Test
    @WithMockUser(username = "user@example.com")
    void searchVideos_shouldReturn403_whenServiceThrowsQuotaExceeded() throws Exception {
        when(searchService.searchVideos("Queen", null, null))
                .thenThrow(new ExternalProviderException("YouTube API request forbidden or quota exceeded", 403));

        mockMvc.perform(get("/api/youtube/videos/search?q=Queen"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_ERROR"));
    }
}
