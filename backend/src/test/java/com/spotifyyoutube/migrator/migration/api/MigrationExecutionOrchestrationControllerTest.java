package com.spotifyyoutube.migrator.migration.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.api.dto.RunMigrationApiRequest;
import com.spotifyyoutube.migrator.migration.application.execution.ApprovedMigrationExecutionService;
import com.spotifyyoutube.migrator.migration.application.execution.ApprovedMigrationOrchestrator;
import com.spotifyyoutube.migrator.migration.application.execution.DestinationPlaylistCreationService;
import com.spotifyyoutube.migrator.migration.application.execution.MigrationExecutionStateService;
import com.spotifyyoutube.migrator.migration.application.execution.OrderedTrackInsertionService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.playlist.domain.Platform;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MigrationExecutionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("Phase 11H: Migration Execution Orchestration Controller WebMvc Test")
class MigrationExecutionOrchestrationControllerTest {

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
    private ApprovedMigrationOrchestrator migrationOrchestrator;

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
        when(userService.getUserById(userId)).thenReturn(testUser);
    }

    @Nested
    @DisplayName("POST /api/migrations/execution/run")
    class RunEndpoint {

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("200 OK: Successfully orchestrates approved migration end-to-end")
        void runsApprovedMigrationSuccessfully() throws Exception {
            MigrationExecutionSummary summary = new MigrationExecutionSummary(
                    executionId,
                    sessionId,
                    UUID.randomUUID(),
                    userId,
                    Platform.YOUTUBE,
                    "yt_pl_target",
                    "https://music.youtube.com/playlist?list=yt_pl_target",
                    "Source Playlist",
                    MigrationExecutionStatus.COMPLETED,
                    2,
                    2,
                    0,
                    0,
                    0,
                    true,
                    false,
                    Instant.now(),
                    Instant.now(),
                    Collections.emptyList()
            );

            when(migrationOrchestrator.executeApprovedMigration(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(summary);

            RunMigrationApiRequest request = new RunMigrationApiRequest(
                    sessionId,
                    userId,
                    "sp_pl_1",
                    "Custom Target Title",
                    "Custom Description",
                    true
            );

            mockMvc.perform(post("/api/migrations/execution/run")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                    .andExpect(jsonPath("$.reviewSessionId").value(sessionId.toString()))
                    .andExpect(jsonPath("$.destinationPlaylistId").value("yt_pl_target"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.successfulInsertions").value(2))
                    .andExpect(jsonPath("$.failedInsertions").value(0));
        }

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("400 Bad Request: Anti-impersonation rejects caller-supplied userId mismatch")
        void rejectsCallerUserIdMismatch() throws Exception {
            UUID differentUserId = UUID.randomUUID();
            RunMigrationApiRequest request = new RunMigrationApiRequest(
                    sessionId,
                    differentUserId,
                    "sp_pl_1",
                    null,
                    null,
                    null
            );

            mockMvc.perform(post("/api/migrations/execution/run")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verify(migrationOrchestrator, never()).executeApprovedMigration(any(ApprovedMigrationExecutionRequest.class), any(UUID.class));
        }

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("404 Not Found: Anti-IDOR when session belongs to another user")
        void antiIdorReturns404() throws Exception {
            when(migrationOrchestrator.executeApprovedMigration(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenThrow(new ResourceNotFoundException("Review session not found: " + sessionId));

            RunMigrationApiRequest request = new RunMigrationApiRequest(
                    sessionId,
                    userId,
                    null,
                    null,
                    null,
                    null
            );

            mockMvc.perform(post("/api/migrations/execution/run")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("409 Conflict: When execution is already RUNNING concurrently")
        void conflictWhenRunningConcurrently() throws Exception {
            when(migrationOrchestrator.executeApprovedMigration(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenThrow(new InvalidStateException("Migration execution is currently in progress"));

            RunMigrationApiRequest request = new RunMigrationApiRequest(
                    sessionId,
                    userId,
                    null,
                    null,
                    null,
                    null
            );

            mockMvc.perform(post("/api/migrations/execution/run")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict());
        }

        @Test
        @WithAnonymousUser
        @DisplayName("401 Unauthorized: Unauthenticated request rejected")
        void unauthenticatedRequestRejected() throws Exception {
            mockMvc.perform(post("/api/migrations/execution/run")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /api/migrations/execution/run/{sessionId}")
    class RunBySessionIdEndpoint {

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("200 OK: Successfully orchestrates approved migration by session ID")
        void runsApprovedMigrationBySessionIdSuccessfully() throws Exception {
            MigrationExecutionSummary summary = new MigrationExecutionSummary(
                    executionId,
                    sessionId,
                    UUID.randomUUID(),
                    userId,
                    Platform.YOUTUBE,
                    "yt_pl_target",
                    "https://music.youtube.com/playlist?list=yt_pl_target",
                    "Source Playlist",
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
                    Collections.emptyList()
            );

            when(migrationOrchestrator.executeApprovedMigration(any(ApprovedMigrationExecutionRequest.class), eq(userId)))
                    .thenReturn(summary);

            mockMvc.perform(post("/api/migrations/execution/run/{sessionId}", sessionId)
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                    .andExpect(jsonPath("$.reviewSessionId").value(sessionId.toString()))
                    .andExpect(jsonPath("$.status").value("COMPLETED"));
        }

        @Test
        @WithAnonymousUser
        @DisplayName("401 Unauthorized: Unauthenticated request rejected")
        void unauthenticatedSessionIdRejected() throws Exception {
            mockMvc.perform(post("/api/migrations/execution/run/{sessionId}", sessionId)
                            .with(csrf()))
                    .andExpect(status().isUnauthorized());
        }
    }
}
