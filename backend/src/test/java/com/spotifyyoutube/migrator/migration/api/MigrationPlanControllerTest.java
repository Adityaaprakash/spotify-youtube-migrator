package com.spotifyyoutube.migrator.migration.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.migration.api.dto.DestinationCandidateResponse;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanPreviewRequest;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanResponse;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanSummaryResponse;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationPlanTrackResponse;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanService;
import com.spotifyyoutube.migrator.migration.application.plan.PlanTrackClassification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MigrationPlanController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@WithMockUser(username = "tester@example.com")
@DisplayName("MigrationPlanController WebMvc Tests")
public class MigrationPlanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MigrationPlanService migrationPlanService;

    @MockBean
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    private User mockAuthenticatedUser(UUID userId) {
        User user = new User();
        user.setId(userId);
        user.setEmail("tester@example.com");
        when(userService.getUserByEmail("tester@example.com")).thenReturn(user);
        return user;
    }

    private MigrationPlanResponse createSamplePlanResponse(UUID planId, String playlistId) {
        DestinationCandidateResponse cand = new DestinationCandidateResponse(
                "yt-1", "Bohemian Rhapsody", "Queen", "Official Video",
                "https://i.ytimg.com/vi/yt-1/default.jpg", "2024-01-01T00:00:00Z",
                Duration.ofSeconds(354)
        );
        MigrationPlanTrackResponse track0 = new MigrationPlanTrackResponse(
                0, "sp-1", "Bohemian Rhapsody", "Queen",
                PlanTrackClassification.AUTO_MATCH, cand, MatchDecision.MATCH,
                0.98, 0.99, List.of("High similarity"), null
        );
        MigrationPlanSummaryResponse summary = new MigrationPlanSummaryResponse(
                1, 1, 0, 0, 0, 0, 0,
                1.0, 0.0, 0.0, 0.0, 0.0, 0.0
        );
        return new MigrationPlanResponse(
                planId, playlistId, "Classic Rock", Instant.now(),
                1, List.of(track0), summary
        );
    }

    @Test
    @DisplayName("Valid preview request with explicit userId returns 200 OK and MigrationPlanResponse")
    void validPreviewRequestWithExplicitUserIdReturnsOk() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        String playlistId = "sp-playlist-100";
        mockAuthenticatedUser(userId);

        MigrationPlanPreviewRequest request = new MigrationPlanPreviewRequest(playlistId, userId, true);
        MigrationPlanResponse planResponse = createSamplePlanResponse(planId, playlistId);

        when(migrationPlanService.generatePreview(eq(userId), any(MigrationPlanPreviewRequest.class)))
                .thenReturn(planResponse);

        mockMvc.perform(post("/api/migrations/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value(planId.toString()))
                .andExpect(jsonPath("$.sourcePlaylistId").value(playlistId))
                .andExpect(jsonPath("$.sourcePlaylistTitle").value("Classic Rock"))
                .andExpect(jsonPath("$.totalTracks").value(1))
                .andExpect(jsonPath("$.tracks[0].position").value(0))
                .andExpect(jsonPath("$.tracks[0].sourceTrackId").value("sp-1"))
                .andExpect(jsonPath("$.tracks[0].classification").value("AUTO_MATCH"))
                .andExpect(jsonPath("$.tracks[0].selectedCandidate.id").value("yt-1"))
                .andExpect(jsonPath("$.summary.totalTracks").value(1))
                .andExpect(jsonPath("$.summary.autoMatchCount").value(1));
    }

    @Test
    @DisplayName("Valid preview request resolving user from authenticated Principal returns 200 OK")
    void validPreviewRequestResolvingUserFromPrincipalReturnsOk() throws Exception {
        UUID resolvedUserId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        String playlistId = "sp-playlist-200";

        mockAuthenticatedUser(resolvedUserId);

        // No userId in request payload; resolved via Principal
        MigrationPlanPreviewRequest request = new MigrationPlanPreviewRequest(playlistId);
        MigrationPlanResponse planResponse = createSamplePlanResponse(planId, playlistId);

        when(migrationPlanService.generatePreview(eq(resolvedUserId), any(MigrationPlanPreviewRequest.class)))
                .thenReturn(planResponse);

        mockMvc.perform(post("/api/migrations/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value(planId.toString()))
                .andExpect(jsonPath("$.sourcePlaylistId").value(playlistId));
    }

    @Test
    @DisplayName("Attempting to supply another user's ID returns 400 Bad Request (anti-impersonation invariant)")
    void attemptToImpersonateAnotherUserReturnsBadRequest() throws Exception {
        UUID authenticatedUserId = UUID.randomUUID();
        UUID impersonatedUserId = UUID.randomUUID();
        mockAuthenticatedUser(authenticatedUserId);

        MigrationPlanPreviewRequest request = new MigrationPlanPreviewRequest("sp-playlist-100", impersonatedUserId);

        mockMvc.perform(post("/api/migrations/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Authenticated user does not match the requested user ID"));
    }

    @Test
    @DisplayName("Authenticated principal not found in user service returns 404 Not Found")
    void authenticatedPrincipalNotFoundReturnsNotFound() throws Exception {
        when(userService.getUserByEmail("tester@example.com"))
                .thenThrow(new ResourceNotFoundException("User not found with email: tester@example.com"));

        MigrationPlanPreviewRequest request = new MigrationPlanPreviewRequest("sp-playlist-100");

        mockMvc.perform(post("/api/migrations/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("User not found with email: tester@example.com"));
    }

    @Test
    @DisplayName("Authenticated principal returning null user returns 404 Not Found")
    void authenticatedPrincipalReturningNullReturnsNotFound() throws Exception {
        when(userService.getUserByEmail("tester@example.com")).thenReturn(null);

        MigrationPlanPreviewRequest request = new MigrationPlanPreviewRequest("sp-playlist-100");

        mockMvc.perform(post("/api/migrations/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("User not found for authenticated principal: tester@example.com"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Unauthenticated request returns 403 Forbidden under security policy")
    void unauthenticatedRequestReturnsForbidden() throws Exception {
        MigrationPlanPreviewRequest request = new MigrationPlanPreviewRequest("sp-100");

        mockMvc.perform(post("/api/migrations/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Unauthenticated request supplying userId is rejected by endpoint security policy")
    void unauthenticatedRequestWithUserIdReturnsForbidden() throws Exception {
        UUID userId = UUID.randomUUID();
        MigrationPlanPreviewRequest request = new MigrationPlanPreviewRequest("sp-100", userId);

        mockMvc.perform(post("/api/migrations/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Missing request body returns 400 Bad Request with INVALID_REQUEST code")
    void missingRequestBodyReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/migrations/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Required request body is missing or malformed"));
    }

    @Test
    @DisplayName("Malformed JSON request body returns 400 Bad Request with INVALID_REQUEST code and no internal traces")
    void malformedJsonRequestBodyReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/migrations/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spotifyPlaylistId\": ")
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Required request body is missing or malformed"));
    }

    @Test
    @DisplayName("Blank or null playlist ID returns 400 Bad Request with validation error")
    void blankPlaylistIdReturnsBadRequest() throws Exception {
        MigrationPlanPreviewRequest emptyRequest = new MigrationPlanPreviewRequest("");

        mockMvc.perform(post("/api/migrations/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyRequest))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        MigrationPlanPreviewRequest whitespaceRequest = new MigrationPlanPreviewRequest("   ");

        mockMvc.perform(post("/api/migrations/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(whitespaceRequest))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("Playlist not found returns 404 Not Found")
    void playlistNotFoundReturnsNotFound() throws Exception {
        UUID userId = UUID.randomUUID();
        mockAuthenticatedUser(userId);
        MigrationPlanPreviewRequest request = new MigrationPlanPreviewRequest("nonexistent-playlist", userId);

        when(migrationPlanService.generatePreview(eq(userId), any(MigrationPlanPreviewRequest.class)))
                .thenThrow(new ResourceNotFoundException("Spotify playlist not found: nonexistent-playlist"));

        mockMvc.perform(post("/api/migrations/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Spotify playlist not found: nonexistent-playlist"));
    }

    @Test
    @DisplayName("Spotify authorization expired returns 401 Unauthorized via ExternalProviderException")
    void spotifyAuthExpiredReturnsUnauthorized() throws Exception {
        UUID userId = UUID.randomUUID();
        mockAuthenticatedUser(userId);
        MigrationPlanPreviewRequest request = new MigrationPlanPreviewRequest("sp-100", userId);

        when(migrationPlanService.generatePreview(eq(userId), any(MigrationPlanPreviewRequest.class)))
                .thenThrow(new ExternalProviderException("Spotify token expired", 401));

        mockMvc.perform(post("/api/migrations/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_ERROR"))
                .andExpect(jsonPath("$.message").value("Spotify token expired"));
    }

    @Test
    @DisplayName("Spotify rate limiting returns 429 Too Many Requests with Retry-After header")
    void spotifyRateLimitingReturnsTooManyRequests() throws Exception {
        UUID userId = UUID.randomUUID();
        mockAuthenticatedUser(userId);
        MigrationPlanPreviewRequest request = new MigrationPlanPreviewRequest("sp-100", userId);

        when(migrationPlanService.generatePreview(eq(userId), any(MigrationPlanPreviewRequest.class)))
                .thenThrow(new ExternalProviderException("Spotify rate limit exceeded", 429, "60"));

        mockMvc.perform(post("/api/migrations/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_ERROR"))
                .andExpect(jsonPath("$.message").value("Spotify rate limit exceeded"));
    }

    @Test
    @DisplayName("Unexpected application failure returns 500 Internal Server Error without leaking internal traces")
    void unexpectedFailureReturnsInternalServerError() throws Exception {
        UUID userId = UUID.randomUUID();
        mockAuthenticatedUser(userId);
        MigrationPlanPreviewRequest request = new MigrationPlanPreviewRequest("sp-100", userId);

        when(migrationPlanService.generatePreview(eq(userId), any(MigrationPlanPreviewRequest.class)))
                .thenThrow(new RuntimeException("Simulated unexpected crash in planning pipeline"));

        mockMvc.perform(post("/api/migrations/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }
}
