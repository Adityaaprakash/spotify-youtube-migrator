package com.spotifyyoutube.migrator.migration.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.migration.api.dto.CreateDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.api.dto.ValidateExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.ApprovedMigrationExecutionService;
import com.spotifyyoutube.migrator.migration.application.execution.DestinationPlaylistCreationService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.DestinationPlaylistCreationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ValidationFailureCode;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MigrationExecutionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("Phase 11A/11B: Migration Execution Controller WebMvc Test")
class MigrationExecutionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ApprovedMigrationExecutionService executionService;

    @MockBean
    private DestinationPlaylistCreationService playlistCreationService;

    @MockBean
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID userId;
    private User testUser;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        testUser = new User("tester@example.com");
        testUser.setId(userId);
        sessionId = UUID.randomUUID();
    }

    private void mockAuthUser() {
        when(userService.getUserByEmail("tester@example.com")).thenReturn(testUser);
    }

    @Nested
    @DisplayName("Validation Endpoint Tests (POST /api/migrations/execution/validate)")
    @WithMockUser(username = "tester@example.com")
    class ValidateExecutionTests {

        @Test
        @DisplayName("Valid execution request returns 200 OK with valid result")
        void validExecutionRequestReturns200() throws Exception {
            mockAuthUser();

            ApprovedTrack track0 = new ApprovedTrack(
                    0, "sp-0", "Song 0", "Artist 0", "yt-0", "Title 0", "Channel 0",
                    Duration.ofSeconds(180), ReviewDecisionSource.AUTOMATIC, 0
            );
            ApprovedMigrationContext context = new ApprovedMigrationContext(
                    sessionId, UUID.randomUUID(), userId, "sp-pl-1", "Source Playlist",
                    "Migrated - Source Playlist", "Description", true,
                    List.of(track0), 1, 1, 0, Instant.now()
            );

            when(executionService.validateExecution(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(ExecutionValidationResult.valid(context));

            ValidateExecutionRequest req = new ValidateExecutionRequest(sessionId, "sp-pl-1", null, null, true, userId);

            mockMvc.perform(post("/api/migrations/execution/validate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(true))
                    .andExpect(jsonPath("$.reviewSessionId").value(sessionId.toString()))
                    .andExpect(jsonPath("$.approvedContext.approvedTrackCount").value(1))
                    .andExpect(jsonPath("$.approvedContext.approvedTracks[0].destinationTrackId").value("yt-0"))
                    .andExpect(jsonPath("$.approvedContext.approvedTracks[0].decisionSource").value("AUTOMATIC"))
                    .andExpect(jsonPath("$.errors").isEmpty());
        }

        @Test
        @DisplayName("Route alias /api/migrations/approved/validate also succeeds")
        void routeAliasValidatesSuccessfully() throws Exception {
            mockAuthUser();

            when(executionService.validateExecution(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(ExecutionValidationResult.invalid(ValidationFailureCode.INCOMPLETE_REVIEW, "Review in progress"));

            ValidateExecutionRequest req = new ValidateExecutionRequest(sessionId, null, null, null, null, null);

            mockMvc.perform(post("/api/migrations/approved/validate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(false))
                    .andExpect(jsonPath("$.errors[0].code").value("INCOMPLETE_REVIEW"))
                    .andExpect(jsonPath("$.errors[0].message").value("Review in progress"));
        }

        @Test
        @DisplayName("Invalid execution returns 200 OK with valid: false and itemized errors")
        void invalidExecutionReturnsErrors() throws Exception {
            mockAuthUser();

            when(executionService.validateExecution(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(ExecutionValidationResult.invalid(
                            ValidationFailureCode.UNRESOLVED_TRACK,
                            1, "sp-1", "Track 1 awaiting review"
                    ));

            ValidateExecutionRequest req = new ValidateExecutionRequest(sessionId, null, null, null, null, null);

            mockMvc.perform(post("/api/migrations/execution/validate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(false))
                    .andExpect(jsonPath("$.approvedContext").doesNotExist())
                    .andExpect(jsonPath("$.errors[0].code").value("UNRESOLVED_TRACK"))
                    .andExpect(jsonPath("$.errors[0].position").value(1))
                    .andExpect(jsonPath("$.errors[0].sourceTrackId").value("sp-1"))
                    .andExpect(jsonPath("$.errors[0].message").value("Track 1 awaiting review"));
        }

        @Test
        @DisplayName("Missing request body returns 400 Bad Request")
        void missingBodyReturns400() throws Exception {
            mockMvc.perform(post("/api/migrations/execution/validate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("Malformed JSON returns 400 Bad Request")
        void malformedJsonReturns400() throws Exception {
            mockMvc.perform(post("/api/migrations/execution/validate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{invalid-json}")
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("Missing reviewSessionId returns 400 Bad Request with validation error")
        void missingSessionIdReturns400() throws Exception {
            mockAuthUser();

            mockMvc.perform(post("/api/migrations/execution/validate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"targetPlaylistTitle\":\"Title\"}")
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("Anti-impersonation: Mismatched userId in request body returns 400 Bad Request")
        void mismatchedUserIdReturns400() throws Exception {
            mockAuthUser();
            UUID victimId = UUID.randomUUID();

            ValidateExecutionRequest req = new ValidateExecutionRequest(sessionId, null, null, null, null, victimId);

            mockMvc.perform(post("/api/migrations/execution/validate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req))
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value("Authenticated user does not match the requested user ID"));

            verify(executionService, never()).validateExecution(any(), any());
        }
    }

    @Nested
    @DisplayName("Eligibility Endpoint Tests (GET /api/migrations/execution/eligibility/{sessionId})")
    @WithMockUser(username = "tester@example.com")
    class EligibilityEndpointTests {

        @Test
        @DisplayName("Check eligibility returns 200 OK with validation result")
        void checkEligibilityReturns200() throws Exception {
            mockAuthUser();

            when(executionService.validateExecution(sessionId, userId))
                    .thenReturn(ExecutionValidationResult.invalid(
                            ValidationFailureCode.UNAUTHORIZED, "Not authorized"
                    ));

            mockMvc.perform(get("/api/migrations/execution/eligibility/" + sessionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(false))
                    .andExpect(jsonPath("$.reviewSessionId").value(sessionId.toString()))
                    .andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
        }

        @Test
        @DisplayName("Missing principal user record returns 404 Not Found")
        void missingPrincipalReturns404() throws Exception {
            when(userService.getUserByEmail("tester@example.com")).thenReturn(null);

            mockMvc.perform(get("/api/migrations/execution/eligibility/" + sessionId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("Destination Playlist Creation Endpoint Tests (POST /api/migrations/execution/create-playlist)")
    @WithMockUser(username = "tester@example.com")
    class CreateDestinationPlaylistTests {

        @Test
        @DisplayName("Valid create-playlist request returns 200 OK with DestinationPlaylistResponse")
        void validCreatePlaylistReturns200() throws Exception {
            mockAuthUser();

            DestinationPlaylistCreationResult result = new DestinationPlaylistCreationResult(
                    sessionId, UUID.randomUUID(), userId, Platform.YOUTUBE, "PL_web_999",
                    "https://www.youtube.com/playlist?list=PL_web_999", "My Playlist",
                    "Description", true, 5, Instant.now()
            );

            when(playlistCreationService.createDestinationPlaylist(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(result);

            CreateDestinationPlaylistRequest reqDto = new CreateDestinationPlaylistRequest(
                    sessionId, userId, "sp-pl-1", "My Playlist", "Description", true
            );

            mockMvc.perform(post("/api/migrations/execution/create-playlist")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reqDto))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reviewSessionId").value(sessionId.toString()))
                    .andExpect(jsonPath("$.destinationPlatform").value("YOUTUBE"))
                    .andExpect(jsonPath("$.destinationPlaylistId").value("PL_web_999"))
                    .andExpect(jsonPath("$.destinationPlaylistUrl").value("https://www.youtube.com/playlist?list=PL_web_999"))
                    .andExpect(jsonPath("$.playlistTitle").value("My Playlist"))
                    .andExpect(jsonPath("$.approvedTrackCount").value(5))
                    .andExpect(jsonPath("$.status").value("CREATED"));
        }

        @Test
        @DisplayName("Route alias /api/migrations/approved/create-playlist returns 200 OK")
        void routeAliasReturns200() throws Exception {
            mockAuthUser();

            DestinationPlaylistCreationResult result = new DestinationPlaylistCreationResult(
                    sessionId, null, userId, Platform.YOUTUBE, "PL_alias_1",
                    "https://www.youtube.com/playlist?list=PL_alias_1", "Alias Playlist",
                    null, true, 1, Instant.now()
            );

            when(playlistCreationService.createDestinationPlaylist(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(result);

            CreateDestinationPlaylistRequest reqDto = new CreateDestinationPlaylistRequest(
                    sessionId, null, null, null, null, null
            );

            mockMvc.perform(post("/api/migrations/approved/create-playlist")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reqDto))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.destinationPlaylistId").value("PL_alias_1"));
        }

        @Test
        @DisplayName("Convenience route /create-playlist/{sessionId} returns 200 OK")
        void convenienceRouteReturns200() throws Exception {
            mockAuthUser();

            DestinationPlaylistCreationResult result = new DestinationPlaylistCreationResult(
                    sessionId, null, userId, Platform.YOUTUBE, "PL_conv_99",
                    "https://www.youtube.com/playlist?list=PL_conv_99", "Convenience Playlist",
                    null, true, 3, Instant.now()
            );

            when(playlistCreationService.createDestinationPlaylist(eq(sessionId), eq(userId)))
                    .thenReturn(result);

            mockMvc.perform(post("/api/migrations/execution/create-playlist/" + sessionId)
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.destinationPlaylistId").value("PL_conv_99"))
                    .andExpect(jsonPath("$.approvedTrackCount").value(3));
        }

        @Test
        @DisplayName("Anti-impersonation: Request body with different userId returns 400 Bad Request")
        void antiImpersonationReturns400() throws Exception {
            mockAuthUser();

            UUID impostorId = UUID.randomUUID();
            CreateDestinationPlaylistRequest reqDto = new CreateDestinationPlaylistRequest(
                    sessionId, impostorId, null, null, null, null
            );

            mockMvc.perform(post("/api/migrations/execution/create-playlist")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reqDto))
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

            verify(playlistCreationService, never()).createDestinationPlaylist(any(ApprovedMigrationExecutionRequest.class), any());
        }

        @Test
        @DisplayName("Missing request body on POST /create-playlist returns 400 Bad Request")
        void missingBodyReturns400() throws Exception {
            mockAuthUser();

            mockMvc.perform(post("/api/migrations/execution/create-playlist")
                            .contentType(MediaType.APPLICATION_JSON)
                            .with(csrf()))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Empty approved track selection returns 409 Conflict")
        void emptyApprovedSelectionReturns409() throws Exception {
            mockAuthUser();

            when(playlistCreationService.createDestinationPlaylist(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenThrow(new InvalidStateException("approved track selection is empty"));

            CreateDestinationPlaylistRequest reqDto = new CreateDestinationPlaylistRequest(
                    sessionId, userId, null, null, null, null
            );

            mockMvc.perform(post("/api/migrations/execution/create-playlist")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reqDto))
                            .with(csrf()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        }

        @Test
        @DisplayName("Session not found or cross-user IDOR returns 404 Not Found")
        void sessionNotFoundReturns404() throws Exception {
            mockAuthUser();

            when(playlistCreationService.createDestinationPlaylist(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenThrow(new ResourceNotFoundException("Review session not found with id: " + sessionId));

            CreateDestinationPlaylistRequest reqDto = new CreateDestinationPlaylistRequest(
                    sessionId, userId, null, null, null, null
            );

            mockMvc.perform(post("/api/migrations/execution/create-playlist")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reqDto))
                            .with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("Provider quota exhaustion (403) maps to HTTP 403 Forbidden")
        void providerQuotaExhaustionReturns403() throws Exception {
            mockAuthUser();

            when(playlistCreationService.createDestinationPlaylist(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenThrow(new ExternalProviderException("YouTube quota exceeded", 403));

            CreateDestinationPlaylistRequest reqDto = new CreateDestinationPlaylistRequest(
                    sessionId, userId, null, null, null, null
            );

            mockMvc.perform(post("/api/migrations/execution/create-playlist")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reqDto))
                            .with(csrf()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_ERROR"));
        }

        @Test
        @DisplayName("Provider timeout maps to HTTP 504 Gateway Timeout")
        void providerTimeoutReturns504() throws Exception {
            mockAuthUser();

            when(playlistCreationService.createDestinationPlaylist(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenThrow(new ResourceAccessException("Connection timed out"));

            CreateDestinationPlaylistRequest reqDto = new CreateDestinationPlaylistRequest(
                    sessionId, userId, null, null, null, null
            );

            mockMvc.perform(post("/api/migrations/execution/create-playlist")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reqDto))
                            .with(csrf()))
                    .andExpect(status().isGatewayTimeout())
                    .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_ERROR"));
        }
    }

    @Nested
    @DisplayName("Security & Authentication Rejections")
    @WithAnonymousUser
    class UnauthenticatedAccessTests {

        @Test
        @DisplayName("Unauthenticated POST /validate is rejected with 401/403")
        void unauthenticatedPostRejected() throws Exception {
            mockMvc.perform(post("/api/migrations/execution/validate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reviewSessionId\":\"" + UUID.randomUUID() + "\"}")
                            .with(csrf()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Unauthenticated POST /create-playlist is rejected with 401/403")
        void unauthenticatedCreatePlaylistRejected() throws Exception {
            mockMvc.perform(post("/api/migrations/execution/create-playlist")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reviewSessionId\":\"" + UUID.randomUUID() + "\"}")
                            .with(csrf()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Unauthenticated GET /eligibility is rejected with 401/403")
        void unauthenticatedGetRejected() throws Exception {
            mockMvc.perform(get("/api/migrations/execution/eligibility/" + UUID.randomUUID()))
                    .andExpect(status().isForbidden());
        }
    }
}
