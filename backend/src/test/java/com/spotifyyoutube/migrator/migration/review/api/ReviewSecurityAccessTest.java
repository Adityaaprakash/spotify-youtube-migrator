package com.spotifyyoutube.migrator.migration.review.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanService;
import com.spotifyyoutube.migrator.migration.review.api.dto.CreateReviewSessionRequest;
import com.spotifyyoutube.migrator.migration.review.application.ReviewCandidateSearchRequest;
import com.spotifyyoutube.migrator.migration.review.application.ReviewSessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReviewSessionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("Review API Security & Ownership Boundary Tests")
public class ReviewSecurityAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReviewSessionService reviewSessionService;

    @MockBean
    private MigrationPlanService migrationPlanService;

    @MockBean
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    private User mockUser(String email, UUID userId) {
        User user = new User();
        user.setId(userId);
        user.setEmail(email);
        when(userService.getUserByEmail(email)).thenReturn(user);
        return user;
    }

    @Nested
    @WithAnonymousUser
    @DisplayName("Unauthenticated Access Rejections")
    class UnauthenticatedAccessTests {

        @Test
        @DisplayName("POST /api/review-sessions rejected with 401/403")
        void createSessionRejected() throws Exception {
            mockMvc.perform(post("/api/review-sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sourcePlaylistId\":\"sp-1\"}")
                            .with(csrf()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /api/review-sessions rejected with 401/403")
        void getSessionsRejected() throws Exception {
            mockMvc.perform(get("/api/review-sessions"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /api/review-sessions/{id} rejected with 401/403")
        void getSessionByIdRejected() throws Exception {
            mockMvc.perform(get("/api/review-sessions/" + UUID.randomUUID()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /api/review-sessions/{id}/complete rejected with 401/403")
        void completeSessionRejected() throws Exception {
            mockMvc.perform(post("/api/review-sessions/" + UUID.randomUUID() + "/complete")
                            .with(csrf()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @WithMockUser(username = "attacker@example.com")
    @DisplayName("IDOR & Ownership Boundary Protection")
    class IdorProtectionTests {

        @Test
        @DisplayName("User cannot read another user's review session (returns 404)")
        void cannotReadOtherUserSession() throws Exception {
            UUID attackerId = UUID.randomUUID();
            UUID victimSessionId = UUID.randomUUID();
            mockUser("attacker@example.com", attackerId);

            when(reviewSessionService.getSessionForUser(victimSessionId, attackerId))
                    .thenThrow(new ResourceNotFoundException("Review session not found"));

            mockMvc.perform(get("/api/review-sessions/" + victimSessionId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("User cannot read tracks of another user's review session (returns 404)")
        void cannotReadOtherUserTracks() throws Exception {
            UUID attackerId = UUID.randomUUID();
            UUID victimSessionId = UUID.randomUUID();
            mockUser("attacker@example.com", attackerId);

            when(reviewSessionService.getTracksForUser(victimSessionId, attackerId))
                    .thenThrow(new ResourceNotFoundException("Review session not found"));

            mockMvc.perform(get("/api/review-sessions/" + victimSessionId + "/tracks"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("User cannot mutate another user's track decision (returns 404)")
        void cannotMutateOtherUserDecision() throws Exception {
            UUID attackerId = UUID.randomUUID();
            UUID victimSessionId = UUID.randomUUID();
            mockUser("attacker@example.com", attackerId);

            when(reviewSessionService.acceptAutomaticMatch(victimSessionId, 0, attackerId))
                    .thenThrow(new ResourceNotFoundException("Review session not found"));

            mockMvc.perform(post("/api/review-sessions/" + victimSessionId + "/tracks/0/accept")
                            .with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("User cannot search candidates on another user's review session (returns 404)")
        void cannotSearchCandidatesOnOtherUserSession() throws Exception {
            UUID attackerId = UUID.randomUUID();
            UUID victimSessionId = UUID.randomUUID();
            mockUser("attacker@example.com", attackerId);

            when(reviewSessionService.searchCandidates(eq(victimSessionId), eq(0), any(ReviewCandidateSearchRequest.class), eq(attackerId)))
                    .thenThrow(new ResourceNotFoundException("Review session not found"));

            mockMvc.perform(post("/api/review-sessions/" + victimSessionId + "/tracks/0/candidates/search")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("User cannot complete another user's review session (returns 404)")
        void cannotCompleteOtherUserSession() throws Exception {
            UUID attackerId = UUID.randomUUID();
            UUID victimSessionId = UUID.randomUUID();
            mockUser("attacker@example.com", attackerId);

            when(reviewSessionService.completeSession(victimSessionId, attackerId))
                    .thenThrow(new ResourceNotFoundException("Review session not found"));

            mockMvc.perform(post("/api/review-sessions/" + victimSessionId + "/complete")
                            .with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("User cannot cancel another user's review session (returns 404)")
        void cannotCancelOtherUserSession() throws Exception {
            UUID attackerId = UUID.randomUUID();
            UUID victimSessionId = UUID.randomUUID();
            mockUser("attacker@example.com", attackerId);

            when(reviewSessionService.cancelSession(victimSessionId, attackerId))
                    .thenThrow(new ResourceNotFoundException("Review session not found"));

            mockMvc.perform(post("/api/review-sessions/" + victimSessionId + "/cancel")
                            .with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Nested
    @WithMockUser(username = "legit@example.com")
    @DisplayName("Anti-Impersonation Protection")
    class AntiImpersonationTests {

        @Test
        @DisplayName("Request providing different userId than authenticated principal is rejected with 400 Bad Request")
        void impersonationRejected() throws Exception {
            UUID legitUserId = UUID.randomUUID();
            UUID victimUserId = UUID.randomUUID();
            mockUser("legit@example.com", legitUserId);

            CreateReviewSessionRequest req = new CreateReviewSessionRequest("spotify-100", victimUserId, true);

            mockMvc.perform(post("/api/review-sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req))
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value("Authenticated user does not match the requested user ID"));

            verify(migrationPlanService, never()).generatePlan(any(), any(), anyBoolean());
            verify(reviewSessionService, never()).createSessionFromPlan(any(), any());
        }

        @Test
        @DisplayName("Authenticated principal with missing User record returns 404 Not Found")
        void principalWithoutUserRecordReturns404() throws Exception {
            when(userService.getUserByEmail("legit@example.com")).thenReturn(null);

            CreateReviewSessionRequest req = new CreateReviewSessionRequest("spotify-100");

            mockMvc.perform(post("/api/review-sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req))
                            .with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }
}
