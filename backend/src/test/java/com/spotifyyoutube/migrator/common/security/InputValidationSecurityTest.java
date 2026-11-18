package com.spotifyyoutube.migrator.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.identity.api.AuthController;
import com.spotifyyoutube.migrator.identity.api.UserController;
import com.spotifyyoutube.migrator.identity.api.dto.LoginRequest;
import com.spotifyyoutube.migrator.identity.api.dto.RegisterRequest;
import com.spotifyyoutube.migrator.identity.application.AuthenticationService;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.identity.repository.UserRepository;
import com.spotifyyoutube.migrator.migration.api.MigrationController;
import com.spotifyyoutube.migrator.migration.api.dto.RunMigrationApiRequest;
import com.spotifyyoutube.migrator.migration.application.MigrationService;
import com.spotifyyoutube.migrator.migration.async.MigrationJobDispatcher;
import com.spotifyyoutube.migrator.migration.progress.application.MigrationProgressService;
import com.spotifyyoutube.migrator.migration.progress.application.ProgressPollingRateLimiter;
import com.spotifyyoutube.migrator.migration.review.api.ReviewSessionController;
import com.spotifyyoutube.migrator.migration.review.api.dto.ExcludeTrackRequest;
import com.spotifyyoutube.migrator.migration.review.api.dto.SelectCandidateRequest;
import com.spotifyyoutube.migrator.migration.review.api.dto.CandidateSelectionDto;
import com.spotifyyoutube.migrator.migration.review.application.ReviewSessionService;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanService;
import com.spotifyyoutube.migrator.spotify.api.SpotifyController;
import com.spotifyyoutube.migrator.spotify.application.SpotifyIdentityService;
import com.spotifyyoutube.migrator.spotify.application.SpotifyPlaylistService;
import com.spotifyyoutube.migrator.youtube.api.YouTubeController;
import com.spotifyyoutube.migrator.youtube.application.YouTubePlaylistService;
import com.spotifyyoutube.migrator.youtube.application.YouTubeSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        AuthController.class,
        UserController.class,
        MigrationController.class,
        ReviewSessionController.class,
        SpotifyController.class,
        YouTubeController.class
})
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
public class InputValidationSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthenticationService authenticationService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private UserService userService;

    @MockBean
    private MigrationService migrationService;

    @MockBean
    private MigrationJobDispatcher jobDispatcher;

    @MockBean
    private MigrationProgressService progressService;

    @MockBean
    private ProgressPollingRateLimiter rateLimiter;

    @MockBean
    private ReviewSessionService reviewSessionService;

    @MockBean
    private MigrationPlanService migrationPlanService;

    @MockBean
    private SpotifyIdentityService spotifyIdentityService;

    @MockBean
    private SpotifyPlaylistService spotifyPlaylistService;

    @MockBean
    private YouTubeSearchService youtubeSearchService;

    @MockBean
    private YouTubePlaylistService youtubePlaylistService;

    @Test
    @DisplayName("Registration with password exceeding 128 chars returns 400 Bad Request")
    void register_OversizedPassword_Returns400() throws Exception {
        String longPassword = "a".repeat(129);
        RegisterRequest request = new RegisterRequest("valid@example.com", longPassword, "Valid Name");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("Registration with invalid email syntax returns 400 Bad Request")
    void register_InvalidEmail_Returns400() throws Exception {
        RegisterRequest request = new RegisterRequest("not-an-email", "validPass123", "Valid Name");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("Login with password exceeding 128 chars returns 400 Bad Request")
    void login_OversizedPassword_Returns400() throws Exception {
        String longPassword = "p".repeat(129);
        LoginRequest request = new LoginRequest("valid@example.com", longPassword);

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @WithMockUser(username = "alice@example.com")
    @DisplayName("Migration submission with target title exceeding 255 chars returns 400 Bad Request")
    void submitMigration_OversizedTitle_Returns400() throws Exception {
        RunMigrationApiRequest request = new RunMigrationApiRequest(
                UUID.randomUUID(),
                null,
                "pl-123",
                "T".repeat(256),
                "Valid description",
                true
        );

        mockMvc.perform(post("/api/migrations/submit")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @WithMockUser(username = "alice@example.com")
    @DisplayName("Track selection with notes exceeding 1000 chars returns 400 Bad Request")
    void selectCandidate_OversizedNotes_Returns400() throws Exception {
        CandidateSelectionDto candidate = new CandidateSelectionDto("v123", "Song Title", "Artist", 180000L);
        SelectCandidateRequest request = new SelectCandidateRequest(candidate, "N".repeat(1001));

        mockMvc.perform(post("/api/review-sessions/" + UUID.randomUUID() + "/tracks/0/select")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @WithMockUser(username = "alice@example.com")
    @DisplayName("YouTube search with query exceeding 500 chars returns 400 Bad Request")
    void youtubeSearch_OversizedQuery_Returns400() throws Exception {
        mockMvc.perform(get("/api/youtube/videos/search")
                .param("q", "Q".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Search query 'q' must not exceed 500 characters"));
    }

    @Test
    @WithMockUser(username = "alice@example.com")
    @DisplayName("YouTube search with invalid maxResults (> 50) returns 400 Bad Request")
    void youtubeSearch_InvalidMaxResults_Returns400() throws Exception {
        mockMvc.perform(get("/api/youtube/videos/search")
                .param("q", "beatles")
                .param("maxResults", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("maxResults must be between 1 and 50"));
    }

    @Test
    @WithMockUser(username = "alice@example.com")
    @DisplayName("Spotify playlists with limit > 50 returns 400 Bad Request")
    void spotifyPlaylists_InvalidLimit_Returns400() throws Exception {
        mockMvc.perform(get("/api/spotify/playlists")
                .param("limit", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 50"));
    }

    @Test
    @WithMockUser(username = "alice@example.com")
    @DisplayName("Spotify playlists with negative offset returns 400 Bad Request")
    void spotifyPlaylists_NegativeOffset_Returns400() throws Exception {
        mockMvc.perform(get("/api/spotify/playlists")
                .param("offset", "-5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("offset must not be negative"));
    }

    @Test
    @WithMockUser(username = "alice@example.com")
    @DisplayName("Malformed UUID in path variable returns 400 Bad Request with safe error message")
    void malformedUuidPathVariable_Returns400() throws Exception {
        mockMvc.perform(get("/api/users/not-a-valid-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Invalid parameter value for: id"));
    }
}
