package com.spotifyyoutube.migrator.youtube.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.youtube.api.dto.AddVideoToPlaylistRequest;
import com.spotifyyoutube.migrator.youtube.api.dto.CreateYouTubePlaylistRequest;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubePlaylistItemResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubePlaylistResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubePrivacyStatus;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoDetailsResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSearchResponse;
import com.spotifyyoutube.migrator.youtube.api.dto.YouTubeVideoSummaryResponse;
import com.spotifyyoutube.migrator.youtube.application.YouTubePlaylistService;
import com.spotifyyoutube.migrator.youtube.application.YouTubeSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(YouTubeController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class YouTubeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private YouTubeSearchService searchService;

    @MockBean
    private YouTubePlaylistService playlistService;

    @MockBean
    private UserService userService;

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

    @Test
    @WithMockUser(username = "user@example.com")
    void getVideoDetails_shouldReturnDetails_whenAuthenticated() throws Exception {
        YouTubeVideoDetailsResponse response = new YouTubeVideoDetailsResponse(
                "v123", "Song Title", "Artist Channel", "Description", "https://img.youtube.com/thumb.jpg", "2023-01-01T00:00:00Z", "PT3M45S"
        );
        when(searchService.getVideoDetails("v123")).thenReturn(response);

        mockMvc.perform(get("/api/youtube/videos/v123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoId").value("v123"))
                .andExpect(jsonPath("$.title").value("Song Title"))
                .andExpect(jsonPath("$.channelTitle").value("Artist Channel"))
                .andExpect(jsonPath("$.duration").value("PT3M45S"));
    }

    @Test
    void getVideoDetails_shouldReturn403_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/youtube/videos/v123"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "user@example.com")
    void getVideoDetails_shouldReturn404_whenVideoNotFound() throws Exception {
        when(searchService.getVideoDetails("unknown"))
                .thenThrow(new ResourceNotFoundException("YouTube video not found: unknown"));

        mockMvc.perform(get("/api/youtube/videos/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("YouTube video not found: unknown"));
    }

    @Test
    @WithMockUser(username = "user@example.com")
    void createPlaylist_shouldReturn201Created_whenValid() throws Exception {
        UUID userId = UUID.randomUUID();
        User mockUser = new User();
        mockUser.setId(userId);
        mockUser.setEmail("user@example.com");

        when(userService.getUserByEmail("user@example.com")).thenReturn(mockUser);

        CreateYouTubePlaylistRequest request = new CreateYouTubePlaylistRequest(
                "My Playlist", "Description", YouTubePrivacyStatus.PRIVATE
        );
        YouTubePlaylistResponse response = new YouTubePlaylistResponse(
                "PL123", "My Playlist", "Description", "private", "2023-01-01T00:00:00Z", "My Channel"
        );

        when(playlistService.createPlaylist(eq(userId), any(CreateYouTubePlaylistRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/youtube/playlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("PL123"))
                .andExpect(jsonPath("$.title").value("My Playlist"))
                .andExpect(jsonPath("$.privacyStatus").value("private"));
    }

    @Test
    void createPlaylist_shouldReturn403_whenUnauthenticated() throws Exception {
        CreateYouTubePlaylistRequest request = new CreateYouTubePlaylistRequest("Title", "Desc", null);

        mockMvc.perform(post("/api/youtube/playlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "user@example.com")
    void createPlaylist_shouldReturn400_whenValidationFails() throws Exception {
        CreateYouTubePlaylistRequest invalidRequest = new CreateYouTubePlaylistRequest("   ", "Desc", null);

        mockMvc.perform(post("/api/youtube/playlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @WithMockUser(username = "user@example.com")
    void createPlaylist_shouldReturn409_whenInvalidStateException() throws Exception {
        UUID userId = UUID.randomUUID();
        User mockUser = new User();
        mockUser.setId(userId);
        mockUser.setEmail("user@example.com");

        when(userService.getUserByEmail("user@example.com")).thenReturn(mockUser);

        CreateYouTubePlaylistRequest request = new CreateYouTubePlaylistRequest("Title", "Desc", null);
        when(playlistService.createPlaylist(eq(userId), any(CreateYouTubePlaylistRequest.class)))
                .thenThrow(new InvalidStateException("Google account is not connected"));

        mockMvc.perform(post("/api/youtube/playlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.message").value("Google account is not connected"));
    }

    @Test
    @WithMockUser(username = "user@example.com")
    void addVideoToPlaylist_shouldReturn201Created_whenValid() throws Exception {
        UUID userId = UUID.randomUUID();
        User mockUser = new User();
        mockUser.setId(userId);
        mockUser.setEmail("user@example.com");

        when(userService.getUserByEmail("user@example.com")).thenReturn(mockUser);

        AddVideoToPlaylistRequest request = new AddVideoToPlaylistRequest("vid456", 0);
        YouTubePlaylistItemResponse response = new YouTubePlaylistItemResponse(
                "pli999", "PL123", "vid456", 0, true
        );

        when(playlistService.addVideoToPlaylist(eq(userId), eq("PL123"), any(AddVideoToPlaylistRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/youtube/playlists/PL123/videos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.playlistItemId").value("pli999"))
                .andExpect(jsonPath("$.playlistId").value("PL123"))
                .andExpect(jsonPath("$.videoId").value("vid456"))
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void addVideoToPlaylist_shouldReturn403_whenUnauthenticated() throws Exception {
        AddVideoToPlaylistRequest request = new AddVideoToPlaylistRequest("vid456", 0);

        mockMvc.perform(post("/api/youtube/playlists/PL123/videos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "user@example.com")
    void addVideoToPlaylist_shouldReturn400_whenValidationFails() throws Exception {
        AddVideoToPlaylistRequest invalidRequest = new AddVideoToPlaylistRequest("   ", 0);

        mockMvc.perform(post("/api/youtube/playlists/PL123/videos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @WithMockUser(username = "user@example.com")
    void addVideoToPlaylist_shouldReturn404_whenPlaylistOrVideoNotFound() throws Exception {
        UUID userId = UUID.randomUUID();
        User mockUser = new User();
        mockUser.setId(userId);
        mockUser.setEmail("user@example.com");

        when(userService.getUserByEmail("user@example.com")).thenReturn(mockUser);

        AddVideoToPlaylistRequest request = new AddVideoToPlaylistRequest("vid456", 0);
        when(playlistService.addVideoToPlaylist(eq(userId), eq("PL123"), any(AddVideoToPlaylistRequest.class)))
                .thenThrow(new ResourceNotFoundException("YouTube playlist or video not found"));

        mockMvc.perform(post("/api/youtube/playlists/PL123/videos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
