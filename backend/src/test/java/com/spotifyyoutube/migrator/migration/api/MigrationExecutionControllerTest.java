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
import com.spotifyyoutube.migrator.migration.api.dto.InitializeExecutionRequest;
import com.spotifyyoutube.migrator.migration.api.dto.InsertTracksRequest;
import com.spotifyyoutube.migrator.migration.api.dto.ValidateExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.ApprovedMigrationExecutionService;
import com.spotifyyoutube.migrator.migration.application.execution.DestinationPlaylistCreationService;
import com.spotifyyoutube.migrator.migration.application.execution.MigrationExecutionStateService;
import com.spotifyyoutube.migrator.migration.application.execution.OrderedTrackInsertionService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.DestinationPlaylistCreationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionTrackRecordDto;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationError;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.InsertTracksExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.PlaylistTrackInsertionSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.TrackInsertionOutcome;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ValidationFailureCode;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
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
import java.util.Optional;
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
@DisplayName("Phase 11A/11B/11C/11D/11E: Migration Execution Controller WebMvc Test")
class MigrationExecutionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ApprovedMigrationExecutionService executionService;

    @MockBean
    private DestinationPlaylistCreationService playlistCreationService;

    @MockBean
    private OrderedTrackInsertionService trackInsertionService;

    @MockBean
    private MigrationExecutionStateService executionStateService;

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

            verify(executionService, never()).validateExecution(any(ApprovedMigrationExecutionRequest.class), any(UUID.class));
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

            verify(playlistCreationService, never()).createDestinationPlaylist(any(ApprovedMigrationExecutionRequest.class), any(UUID.class));
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
    @DisplayName("Track Insertion Endpoint Tests (POST /api/migrations/execution/insert-tracks)")
    @WithMockUser(username = "tester@example.com")
    class InsertApprovedTracksTests {

        private PlaylistTrackInsertionSummary createMockSummary(String playlistId) {
            TrackInsertionOutcome o0 = TrackInsertionOutcome.success(0, 0, "sp-0", "Song 0", "Artist 0", "yt-0", "item-0");
            TrackInsertionOutcome o1 = TrackInsertionOutcome.success(1, 1, "sp-1", "Song 1", "Artist 1", "yt-1", "item-1");

            return new PlaylistTrackInsertionSummary(
                    sessionId, UUID.randomUUID(), userId, Platform.YOUTUBE, playlistId,
                    2, 2, 0, 0, 0,
                    List.of(o0, o1),
                    Instant.now().minusSeconds(1), Instant.now()
            );
        }

        @Test
        @DisplayName("Valid track insertion request returns 200 OK with detailed outcomes")
        void validInsertionReturns200() throws Exception {
            mockAuthUser();

            PlaylistTrackInsertionSummary summary = createMockSummary("PL_created_123");
            when(trackInsertionService.insertApprovedTracks(any(InsertTracksExecutionRequest.class), eq(userId)))
                    .thenReturn(summary);

            InsertTracksRequest reqDto = new InsertTracksRequest(sessionId, "PL_created_123", userId);

            mockMvc.perform(post("/api/migrations/execution/insert-tracks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reqDto))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reviewSessionId").value(sessionId.toString()))
                    .andExpect(jsonPath("$.destinationPlaylistId").value("PL_created_123"))
                    .andExpect(jsonPath("$.destinationPlatform").value("YOUTUBE"))
                    .andExpect(jsonPath("$.totalApprovedTracks").value(2))
                    .andExpect(jsonPath("$.successfulInsertions").value(2))
                    .andExpect(jsonPath("$.failedInsertions").value(0))
                    .andExpect(jsonPath("$.uncertainInsertions").value(0))
                    .andExpect(jsonPath("$.skippedInsertions").value(0))
                    .andExpect(jsonPath("$.fullySuccessful").value(true))
                    .andExpect(jsonPath("$.executionStatus").value("COMPLETED"))
                    .andExpect(jsonPath("$.trackOutcomes").isArray())
                    .andExpect(jsonPath("$.trackOutcomes[0].targetPosition").value(0))
                    .andExpect(jsonPath("$.trackOutcomes[0].destinationTrackId").value("yt-0"))
                    .andExpect(jsonPath("$.trackOutcomes[0].destinationPlaylistItemId").value("item-0"))
                    .andExpect(jsonPath("$.trackOutcomes[0].status").value("SUCCESS"))
                    .andExpect(jsonPath("$.trackOutcomes[1].targetPosition").value(1))
                    .andExpect(jsonPath("$.trackOutcomes[1].destinationTrackId").value("yt-1"))
                    .andExpect(jsonPath("$.trackOutcomes[1].status").value("SUCCESS"));
        }

        @Test
        @DisplayName("Dual-route alias POST /api/migrations/approved/insert-tracks also returns 200 OK")
        void dualRouteAliasWorks() throws Exception {
            mockAuthUser();

            PlaylistTrackInsertionSummary summary = createMockSummary("PL_alias_123");
            when(trackInsertionService.insertApprovedTracks(any(InsertTracksExecutionRequest.class), eq(userId)))
                    .thenReturn(summary);

            InsertTracksRequest reqDto = new InsertTracksRequest(sessionId, "PL_alias_123", null);

            mockMvc.perform(post("/api/migrations/approved/insert-tracks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reqDto))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.destinationPlaylistId").value("PL_alias_123"));
        }

        @Test
        @DisplayName("Convenience route POST /insert-tracks/{sessionId} returns 200 OK")
        void convenienceRouteReturns200() throws Exception {
            mockAuthUser();

            PlaylistTrackInsertionSummary summary = createMockSummary("PL_conv_123");
            when(trackInsertionService.insertApprovedTracks(any(InsertTracksExecutionRequest.class), eq(userId)))
                    .thenReturn(summary);

            mockMvc.perform(post("/api/migrations/execution/insert-tracks/" + sessionId)
                            .param("destinationPlaylistId", "PL_conv_123")
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.destinationPlaylistId").value("PL_conv_123"))
                    .andExpect(jsonPath("$.fullySuccessful").value(true));
        }

        @Test
        @DisplayName("Anti-impersonation: caller-supplied userId differing from authenticated user returns 400 Bad Request")
        void impersonationReturns400() throws Exception {
            mockAuthUser();

            UUID maliciousUserId = UUID.randomUUID();
            InsertTracksRequest reqDto = new InsertTracksRequest(sessionId, "PL_impersonate", maliciousUserId);

            mockMvc.perform(post("/api/migrations/execution/insert-tracks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reqDto))
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

            verify(trackInsertionService, never()).insertApprovedTracks(any(InsertTracksExecutionRequest.class), any(UUID.class));
        }

        @Test
        @DisplayName("Review session not found maps to HTTP 404 Not Found")
        void sessionNotFoundReturns404() throws Exception {
            mockAuthUser();

            when(trackInsertionService.insertApprovedTracks(any(InsertTracksExecutionRequest.class), eq(userId)))
                    .thenThrow(new ResourceNotFoundException("Review session not found: " + sessionId));

            InsertTracksRequest reqDto = new InsertTracksRequest(sessionId, "PL_missing", null);

            mockMvc.perform(post("/api/migrations/execution/insert-tracks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reqDto))
                            .with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }

        @Test
        @DisplayName("Ineligible session (no approved tracks) maps to HTTP 409 Conflict")
        void ineligibleSessionReturns409() throws Exception {
            mockAuthUser();

            when(trackInsertionService.insertApprovedTracks(any(InsertTracksExecutionRequest.class), eq(userId)))
                    .thenThrow(new InvalidStateException("Cannot insert tracks into destination playlist: no approved tracks available"));

            InsertTracksRequest reqDto = new InsertTracksRequest(sessionId, "PL_empty", null);

            mockMvc.perform(post("/api/migrations/execution/insert-tracks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reqDto))
                            .with(csrf()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        }

        @Test
        @DisplayName("Provider quota exhaustion maps to HTTP 403 Forbidden")
        void quotaExhaustionReturns403() throws Exception {
            mockAuthUser();

            when(trackInsertionService.insertApprovedTracks(any(InsertTracksExecutionRequest.class), eq(userId)))
                    .thenThrow(new ExternalProviderException("YouTube API quota exceeded (quotaExceeded)"));

            InsertTracksRequest reqDto = new InsertTracksRequest(sessionId, "PL_quota", null);

            mockMvc.perform(post("/api/migrations/execution/insert-tracks")
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

            when(trackInsertionService.insertApprovedTracks(any(InsertTracksExecutionRequest.class), eq(userId)))
                    .thenThrow(new ResourceAccessException("I/O error on POST request: Read timed out"));

            InsertTracksRequest reqDto = new InsertTracksRequest(sessionId, "PL_timeout", null);

            mockMvc.perform(post("/api/migrations/execution/insert-tracks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reqDto))
                            .with(csrf()))
                    .andExpect(status().isGatewayTimeout())
                    .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_ERROR"));
        }
    }

    @Nested
    @DisplayName("Phase 11E: Durable Execution State & Resume Tests")
    @WithMockUser(username = "tester@example.com")
    class DurableExecutionEndpointTests {

        private UUID executionId;
        private MigrationExecutionSummary sampleSummary;

        @BeforeEach
        void initSummary() {
            executionId = UUID.randomUUID();
            ExecutionTrackRecordDto trackDto = new ExecutionTrackRecordDto(
                    UUID.randomUUID(),
                    0,
                    0,
                    "sp_1",
                    "Track 1",
                    "Artist 1",
                    "yt_1",
                    "item_1",
                    ExecutionTrackStatus.ADDED,
                    null,
                    1,
                    Instant.now(),
                    Instant.now()
            );

            sampleSummary = new MigrationExecutionSummary(
                    executionId,
                    sessionId,
                    Optional.of(UUID.randomUUID()),
                    userId,
                    Platform.YOUTUBE.name(),
                    "PL_dest_123",
                    Optional.of("https://music.youtube.com/playlist?list=PL_dest_123"),
                    "Target Playlist",
                    MigrationExecutionStatus.COMPLETED,
                    1,
                    1,
                    0,
                    0,
                    0,
                    true,
                    false,
                    Instant.now(),
                    Instant.now(),
                    List.of(trackDto)
            );
        }

        @Test
        @DisplayName("POST /initialize succeeds and returns durable execution state")
        void initializeExecutionSuccess() throws Exception {
            mockAuthUser();

            when(executionStateService.initializeExecution(any(com.spotifyyoutube.migrator.migration.application.execution.contract.InitializeExecutionRequest.class), eq(userId)))
                    .thenReturn(sampleSummary);

            InitializeExecutionRequest reqDto = new InitializeExecutionRequest(
                    sessionId,
                    "PL_dest_123",
                    "https://music.youtube.com/playlist?list=PL_dest_123",
                    userId
            );

            mockMvc.perform(post("/api/migrations/execution/initialize")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reqDto))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                    .andExpect(jsonPath("$.reviewSessionId").value(sessionId.toString()))
                    .andExpect(jsonPath("$.destinationPlaylistId").value("PL_dest_123"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.successfulInsertions").value(1))
                    .andExpect(jsonPath("$.trackRecords[0].destinationTrackId").value("yt_1"));
        }

        @Test
        @DisplayName("POST /initialize fails with 400 when user ID impersonation is attempted")
        void initializeImpersonationRejected() throws Exception {
            mockAuthUser();

            InitializeExecutionRequest reqDto = new InitializeExecutionRequest(
                    sessionId,
                    "PL_dest_123",
                    null,
                    UUID.randomUUID() // Different from authenticated user
            );

            mockMvc.perform(post("/api/migrations/execution/initialize")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reqDto))
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }

        @Test
        @DisplayName("GET /{executionId} retrieves durable execution summary")
        void getExecutionSuccess() throws Exception {
            mockAuthUser();

            when(executionStateService.getExecution(eq(executionId), eq(userId)))
                    .thenReturn(sampleSummary);

            mockMvc.perform(get("/api/migrations/execution/" + executionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                    .andExpect(jsonPath("$.status").value("COMPLETED"));
        }

        @Test
        @DisplayName("GET /{executionId} returns 404 when execution does not exist")
        void getExecutionNotFound() throws Exception {
            mockAuthUser();

            UUID missingId = UUID.randomUUID();
            when(executionStateService.getExecution(eq(missingId), eq(userId)))
                    .thenThrow(new ResourceNotFoundException("MigrationExecution not found"));

            mockMvc.perform(get("/api/migrations/execution/" + missingId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }

        @Test
        @DisplayName("GET /session/{sessionId} retrieves execution summary by review session ID")
        void getExecutionBySessionSuccess() throws Exception {
            mockAuthUser();

            when(executionStateService.getExecutionByReviewSession(eq(sessionId), eq(userId)))
                    .thenReturn(sampleSummary);

            mockMvc.perform(get("/api/migrations/execution/session/" + sessionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reviewSessionId").value(sessionId.toString()));
        }

        @Test
        @DisplayName("POST /{executionId}/resume resumes eligible execution")
        void resumeExecutionSuccess() throws Exception {
            mockAuthUser();

            when(executionStateService.resumeExecution(eq(executionId), eq(userId)))
                    .thenReturn(sampleSummary);

            mockMvc.perform(post("/api/migrations/execution/" + executionId + "/resume")
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.executionId").value(executionId.toString()));
        }

        @Test
        @DisplayName("POST /{executionId}/resume returns 409 Conflict when execution is currently running")
        void resumeExecutionConflictWhenRunning() throws Exception {
            mockAuthUser();

            when(executionStateService.resumeExecution(eq(executionId), eq(userId)))
                    .thenThrow(new InvalidStateException("Execution is currently running"));

            mockMvc.perform(post("/api/migrations/execution/" + executionId + "/resume")
                            .with(csrf()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE"));
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
        @DisplayName("Unauthenticated POST /insert-tracks is rejected with 401/403")
        void unauthenticatedInsertTracksRejected() throws Exception {
            mockMvc.perform(post("/api/migrations/execution/insert-tracks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reviewSessionId\":\"" + UUID.randomUUID() + "\",\"destinationPlaylistId\":\"PL_123\"}")
                            .with(csrf()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Unauthenticated GET /eligibility is rejected with 401/403")
        void unauthenticatedGetRejected() throws Exception {
            mockMvc.perform(get("/api/migrations/execution/eligibility/" + UUID.randomUUID()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Unauthenticated POST /initialize is rejected with 401/403")
        void unauthenticatedInitializeRejected() throws Exception {
            mockMvc.perform(post("/api/migrations/execution/initialize")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reviewSessionId\":\"" + UUID.randomUUID() + "\",\"destinationPlaylistId\":\"PL_123\"}")
                            .with(csrf()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Unauthenticated GET /{executionId} is rejected with 401/403")
        void unauthenticatedGetExecutionRejected() throws Exception {
            mockMvc.perform(get("/api/migrations/execution/" + UUID.randomUUID()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Unauthenticated POST /{executionId}/resume is rejected with 401/403")
        void unauthenticatedResumeRejected() throws Exception {
            mockMvc.perform(post("/api/migrations/execution/" + UUID.randomUUID() + "/resume")
                            .with(csrf()))
                    .andExpect(status().isForbidden());
        }
    }
}
