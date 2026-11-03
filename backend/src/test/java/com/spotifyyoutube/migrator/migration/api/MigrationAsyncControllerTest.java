package com.spotifyyoutube.migrator.migration.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.api.dto.RunMigrationApiRequest;
import com.spotifyyoutube.migrator.migration.application.MigrationService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.async.MigrationJobDispatcher;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.progress.application.MigrationProgressService;
import com.spotifyyoutube.migrator.migration.progress.domain.MigrationProgressSnapshot;
import com.spotifyyoutube.migrator.migration.api.dto.CreateMigrationRequest;
import com.spotifyyoutube.migrator.migration.domain.MigrationJob;
import com.spotifyyoutube.migrator.migration.domain.MigrationStatus;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvc integration tests for asynchronous migration submission and progress retrieval (Phase 12E).
 */
@WebMvcTest(MigrationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@WithMockUser(username = "tester@example.com")
@DisplayName("Phase 12E: MigrationController Async & Progress Tests")
class MigrationAsyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MigrationService migrationService;

    @MockBean
    private MigrationJobDispatcher jobDispatcher;

    @MockBean
    private MigrationProgressService progressService;

    @MockBean
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID userId;
    private User testUser;
    private UUID sessionId;
    private UUID executionId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        testUser = new User("tester@example.com");
        testUser.setId(userId);

        sessionId = UUID.randomUUID();
        executionId = UUID.randomUUID();

        when(userService.getUserByEmail("tester@example.com")).thenReturn(testUser);
    }

    @Nested
    @DisplayName("Async Submission Tests (POST /api/migrations)")
    class SubmissionTests {

        @Test
        @DisplayName("Valid approved migration returns 202 Accepted with Location header")
        void submitMigration_Success() throws Exception {
            RunMigrationApiRequest request = new RunMigrationApiRequest(
                    sessionId,
                    userId,
                    "sp-pl-1",
                    "My Target Title",
                    "Target Desc",
                    true
            );

            MigrationExecutionSummary summary = new MigrationExecutionSummary(
                    executionId,
                    sessionId,
                    UUID.randomUUID(),
                    "sp-pl-1",
                    "Source Title",
                    "PL_target",
                    "https://music.youtube.com/playlist?list=PL_target",
                    MigrationExecutionStatus.QUEUED,
                    10,
                    0, 0, 0, 0, 0,
                    Instant.now(),
                    null,
                    List.of()
            );

            when(jobDispatcher.dispatchMigration(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(summary);

            mockMvc.perform(post("/api/migrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
                    .with(csrf()))
                    .andExpect(status().isAccepted())
                    .andExpect(header().string("Location", "/api/migrations/" + executionId + "/progress"))
                    .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                    .andExpect(jsonPath("$.reviewSessionId").value(sessionId.toString()))
                    .andExpect(jsonPath("$.status").value("QUEUED"))
                    .andExpect(jsonPath("$.progressUrl").value("/api/migrations/" + executionId + "/progress"))
                    .andExpect(jsonPath("$.totalTracks").value(10))
                    .andExpect(jsonPath("$.message").value("Migration request accepted and queued for background processing"));

            verify(jobDispatcher).dispatchMigration(any(ApprovedMigrationExecutionRequest.class), eq(userId));
        }

        @Test
        @DisplayName("Idempotent submission for already COMPLETED execution returns 200 OK with completed status")
        void submitMigration_AlreadyCompleted() throws Exception {
            RunMigrationApiRequest request = new RunMigrationApiRequest(
                    sessionId,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            MigrationExecutionSummary summary = new MigrationExecutionSummary(
                    executionId,
                    sessionId,
                    UUID.randomUUID(),
                    "sp-pl-1",
                    "Source Title",
                    "PL_target",
                    "https://music.youtube.com/playlist?list=PL_target",
                    MigrationExecutionStatus.COMPLETED,
                    15,
                    15, 0, 0, 0, 0,
                    Instant.now().minusSeconds(60),
                    Instant.now(),
                    List.of()
            );

            when(jobDispatcher.dispatchMigration(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(summary);

            mockMvc.perform(post("/api/migrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
                    .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.message").value("Migration is already COMPLETED"));
        }

        @Test
        @DisplayName("Submission rejects caller when client-supplied userId does not match principal")
        void submitMigration_AntiImpersonation() throws Exception {
            UUID differentUserId = UUID.randomUUID();
            RunMigrationApiRequest request = new RunMigrationApiRequest(
                    sessionId,
                    differentUserId,
                    null, null, null, null
            );

            mockMvc.perform(post("/api/migrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
                    .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

            verify(jobDispatcher, never()).dispatchMigration(any(), any());
        }

        @Test
        @DisplayName("Submission rejects unauthenticated caller with 401 Unauthorized")
        void submitMigration_Unauthenticated() throws Exception {
            RunMigrationApiRequest request = new RunMigrationApiRequest(
                    sessionId,
                    null, null, null, null, null
            );

            mockMvc.perform(post("/api/migrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
                    .with(anonymous())
                    .with(csrf()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }

        @Test
        @DisplayName("Unauthorized review session throws ResourceNotFoundException mapped to 404")
        void submitMigration_UnauthorizedReviewSession() throws Exception {
            RunMigrationApiRequest request = new RunMigrationApiRequest(
                    sessionId,
                    null, null, null, null, null
            );

            when(jobDispatcher.dispatchMigration(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenThrow(new ResourceNotFoundException("Review session not found: " + sessionId));

            mockMvc.perform(post("/api/migrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
                    .with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("Conflict on already RUNNING execution throws InvalidStateException mapped to 409")
        void submitMigration_AlreadyRunning() throws Exception {
            RunMigrationApiRequest request = new RunMigrationApiRequest(
                    sessionId,
                    null, null, null, null, null
            );

            when(jobDispatcher.dispatchMigration(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenThrow(new InvalidStateException("Migration execution is already in progress"));

            mockMvc.perform(post("/api/migrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
                    .with(csrf()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        }

        @Test
        @DisplayName("Executor queue saturation returns 429 Too Many Requests with Retry-After header")
        void submitMigration_ExecutorSaturated() throws Exception {
            RunMigrationApiRequest request = new RunMigrationApiRequest(
                    sessionId,
                    null, null, null, null, null
            );

            when(jobDispatcher.dispatchMigration(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenThrow(new TaskRejectedException("Worker pool queue capacity saturated"));

            mockMvc.perform(post("/api/migrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
                    .with(csrf()))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "30"))
                    .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                    .andExpect(jsonPath("$.message").value("Migration worker pool is currently saturated; please retry shortly"));
        }

        @Test
        @DisplayName("Empty request payload is rejected with 400 Bad Request")
        void submitMigration_EmptyBody() throws Exception {
            mockMvc.perform(post("/api/migrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
                    .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("Approved request without reviewSessionId is rejected with 400 Bad Request")
        void submitMigration_MissingReviewSessionId() throws Exception {
            mockMvc.perform(post("/api/migrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"targetPlaylistTitle\": \"Target Title Only\"}")
                    .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("Unrecognized payload neither async nor legacy is rejected with 400 Bad Request")
        void submitMigration_UnrecognizedPayload() throws Exception {
            mockMvc.perform(post("/api/migrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"arbitraryKey\": \"arbitraryValue\"}")
                    .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("Legacy synchronous migration request routes to legacy handler returning 201 Created")
        void submitMigration_LegacyCompatibility() throws Exception {
            CreateMigrationRequest legacyRequest = new CreateMigrationRequest(
                    userId,
                    "spotify-pl-1",
                    Platform.SPOTIFY,
                    Platform.YOUTUBE
            );

            MigrationJob mockJob = new MigrationJob();
            mockJob.setUser(testUser);
            mockJob.setSourcePlaylistId("spotify-pl-1");
            mockJob.setSourcePlatform(Platform.SPOTIFY);
            mockJob.setTargetPlatform(Platform.YOUTUBE);
            mockJob.setStatus(MigrationStatus.PENDING);

            when(migrationService.createMigrationJob(any(CreateMigrationRequest.class))).thenReturn(mockJob);

            mockMvc.perform(post("/api/migrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(legacyRequest))
                    .with(csrf()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sourcePlaylistId").value("spotify-pl-1"));
        }
    }


    @Nested
    @DisplayName("Execution Progress Tests (GET /api/migrations/{executionId}/progress)")
    class ExecutionProgressTests {

        @Test
        @DisplayName("Returns 200 OK with accurate progress snapshot for valid execution")
        void getExecutionProgress_Success() throws Exception {
            MigrationProgressSnapshot snapshot = MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.RUNNING,
                    100, // total
                    20,  // pending
                    1,   // adding
                    65,  // added
                    5,   // failed
                    2,   // uncertain
                    7,   // skipped
                    0,   // rollingBack
                    0    // rolledBack
            );

            when(progressService.getProgress(executionId, userId)).thenReturn(snapshot);

            mockMvc.perform(get("/api/migrations/{executionId}/progress", executionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                    .andExpect(jsonPath("$.status").value("RUNNING"))
                    .andExpect(jsonPath("$.totalTracks").value(100))
                    .andExpect(jsonPath("$.pendingTracks").value(20))
                    .andExpect(jsonPath("$.addingTracks").value(1))
                    .andExpect(jsonPath("$.addedTracks").value(65))
                    .andExpect(jsonPath("$.failedTracks").value(5))
                    .andExpect(jsonPath("$.uncertainTracks").value(2))
                    .andExpect(jsonPath("$.skippedTracks").value(7))
                    .andExpect(jsonPath("$.rollingBackTracks").value(0))
                    .andExpect(jsonPath("$.rolledBackTracks").value(0))
                    .andExpect(jsonPath("$.processedTracks").value(79)) // 65 + 5 + 2 + 7 = 79
                    .andExpect(jsonPath("$.remainingTracks").value(21))  // 100 - 79 = 21
                    .andExpect(jsonPath("$.progressPercentage").value(79.0));

            verify(progressService).getProgress(executionId, userId);
        }

        @Test
        @DisplayName("Missing execution returns 404 NOT_FOUND")
        void getExecutionProgress_NotFound() throws Exception {
            when(progressService.getProgress(executionId, userId))
                    .thenThrow(new ResourceNotFoundException("Migration execution not found: " + executionId));

            mockMvc.perform(get("/api/migrations/{executionId}/progress", executionId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("Unauthorized execution returns 404 NOT_FOUND (anti-IDOR)")
        void getExecutionProgress_AntiIdor() throws Exception {
            when(progressService.getProgress(executionId, userId))
                    .thenThrow(new ResourceNotFoundException("Migration execution not found: " + executionId));

            mockMvc.perform(get("/api/migrations/{executionId}/progress", executionId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("Unauthenticated request returns 401 Unauthorized")
        void getExecutionProgress_Unauthenticated() throws Exception {
            mockMvc.perform(get("/api/migrations/{executionId}/progress", executionId)
                    .with(anonymous()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Nested
    @DisplayName("Review Session Progress Tests (GET /api/migrations/review-sessions/{reviewSessionId}/progress)")
    class ReviewSessionProgressTests {

        @Test
        @DisplayName("Returns 200 OK with progress snapshot for valid review session")
        void getReviewSessionProgress_Success() throws Exception {
            MigrationProgressSnapshot snapshot = MigrationProgressSnapshot.calculate(
                    executionId,
                    MigrationExecutionStatus.QUEUED,
                    50,
                    50, 0, 0, 0, 0, 0, 0, 0
            );

            when(progressService.getProgressByReviewSession(sessionId, userId)).thenReturn(snapshot);

            mockMvc.perform(get("/api/migrations/review-sessions/{reviewSessionId}/progress", sessionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                    .andExpect(jsonPath("$.status").value("QUEUED"))
                    .andExpect(jsonPath("$.totalTracks").value(50))
                    .andExpect(jsonPath("$.pendingTracks").value(50))
                    .andExpect(jsonPath("$.processedTracks").value(0))
                    .andExpect(jsonPath("$.progressPercentage").value(0.0));

            verify(progressService).getProgressByReviewSession(sessionId, userId);
        }

        @Test
        @DisplayName("Missing review session returns 404 NOT_FOUND")
        void getReviewSessionProgress_NotFound() throws Exception {
            when(progressService.getProgressByReviewSession(sessionId, userId))
                    .thenThrow(new ResourceNotFoundException("Migration execution not found for review session: " + sessionId));

            mockMvc.perform(get("/api/migrations/review-sessions/{reviewSessionId}/progress", sessionId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("Unauthorized review session returns 404 NOT_FOUND (anti-IDOR)")
        void getReviewSessionProgress_AntiIdor() throws Exception {
            when(progressService.getProgressByReviewSession(sessionId, userId))
                    .thenThrow(new ResourceNotFoundException("Migration execution not found for review session: " + sessionId));

            mockMvc.perform(get("/api/migrations/review-sessions/{reviewSessionId}/progress", sessionId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("Unauthenticated request returns 401 Unauthorized")
        void getReviewSessionProgress_Unauthenticated() throws Exception {
            mockMvc.perform(get("/api/migrations/review-sessions/{reviewSessionId}/progress", sessionId)
                    .with(anonymous()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }
}

