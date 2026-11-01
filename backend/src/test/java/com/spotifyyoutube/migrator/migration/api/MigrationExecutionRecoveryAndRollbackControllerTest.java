package com.spotifyyoutube.migrator.migration.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.api.dto.RecoverExecutionApiRequest;
import com.spotifyyoutube.migrator.migration.api.dto.RollbackExecutionApiRequest;
import com.spotifyyoutube.migrator.migration.application.execution.ApprovedMigrationExecutionService;
import com.spotifyyoutube.migrator.migration.application.execution.DestinationPlaylistCreationService;
import com.spotifyyoutube.migrator.migration.application.execution.MigrationExecutionStateService;
import com.spotifyyoutube.migrator.migration.application.execution.OrderedTrackInsertionService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionRollbackSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RecoverExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackRecordDto;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackTrackOutcomeDto;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.RollbackExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.RollbackRecordStatus;
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
@DisplayName("Phase 11F: Migration Execution Recovery & Rollback Controller WebMvc Test")
class MigrationExecutionRecoveryAndRollbackControllerTest {

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
    private UUID executionId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        testUser = new User("tester@example.com");
        testUser.setId(userId);
        executionId = UUID.randomUUID();

        when(userService.getUserByEmail("tester@example.com")).thenReturn(testUser);
        when(userService.getUserById(userId)).thenReturn(testUser);
    }

    @Nested
    @DisplayName("POST /api/migrations/execution/{executionId}/recover")
    class RecoverExecutionEndpoint {

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("200 OK: Successfully recovers eligible failed execution")
        void recoversExecutionSuccessfully() throws Exception {
            MigrationExecutionSummary summary = new MigrationExecutionSummary(
                    executionId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    userId,
                    "yt_pl_target",
                    MigrationExecutionStatus.COMPLETED,
                    2,
                    2,
                    0,
                    0,
                    0,
                    0,
                    0,
                    Collections.emptyList(),
                    true,
                    false,
                    Instant.now(),
                    Instant.now()
            );

            when(executionStateService.recoverExecution(any(RecoverExecutionRequest.class), eq(userId)))
                    .thenReturn(summary);

            RecoverExecutionApiRequest request = new RecoverExecutionApiRequest(userId, "Retry failed tracks");

            mockMvc.perform(post("/api/migrations/execution/{executionId}/recover", executionId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.successfulInsertions").value(2))
                    .andExpect(jsonPath("$.failedInsertions").value(0))
                    .andExpect(jsonPath("$.canRollback").value(true));
        }

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("400 Bad Request: Anti-impersonation rejects caller-supplied userId mismatch")
        void rejectsCallerUserIdMismatch() throws Exception {
            UUID differentUserId = UUID.randomUUID();
            RecoverExecutionApiRequest request = new RecoverExecutionApiRequest(differentUserId, "Spoofed user");

            mockMvc.perform(post("/api/migrations/execution/{executionId}/recover", executionId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verify(executionStateService, never()).recoverExecution(any(), any());
        }

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("404 Not Found: Anti-IDOR when execution belongs to another user")
        void antiIdorReturns404() throws Exception {
            when(executionStateService.recoverExecution(any(RecoverExecutionRequest.class), eq(userId)))
                    .thenThrow(new ResourceNotFoundException("MigrationExecution not found for ID: " + executionId));

            mockMvc.perform(post("/api/migrations/execution/{executionId}/recover", executionId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound());
        }

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("409 Conflict: Rejects recovery when execution has UNCERTAIN tracks or is COMPLETED")
        void conflictOnUncertainOrCompleted() throws Exception {
            when(executionStateService.recoverExecution(any(RecoverExecutionRequest.class), eq(userId)))
                    .thenThrow(new InvalidStateException("Execution contains UNCERTAIN tracks"));

            mockMvc.perform(post("/api/migrations/execution/{executionId}/recover", executionId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isConflict());
        }

        @Test
        @WithAnonymousUser
        @DisplayName("401 Unauthorized: Unauthenticated request rejected")
        void unauthenticatedRecoveryRejected() throws Exception {
            mockMvc.perform(post("/api/migrations/execution/{executionId}/recover", executionId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /api/migrations/execution/{executionId}/rollback")
    class RollbackExecutionEndpoint {

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("200 OK: Successfully rolls back target playlist items")
        void rollsBackExecutionSuccessfully() throws Exception {
            ExecutionRollbackSummary summary = new ExecutionRollbackSummary(
                    executionId,
                    userId,
                    "yt_pl_target",
                    RollbackExecutionStatus.SUCCESS,
                    1,
                    1,
                    0,
                    0,
                    List.of(new RollbackTrackOutcomeDto(
                            UUID.randomUUID(), "yt_item_0", RollbackRecordStatus.ROLLED_BACK, null, Instant.now()
                    )),
                    Instant.now()
            );

            when(executionStateService.rollbackExecution(any(RollbackExecutionRequest.class), eq(userId)))
                    .thenReturn(summary);

            RollbackExecutionApiRequest request = new RollbackExecutionApiRequest(
                    userId, List.of("yt_item_0"), "Clean up song");

            mockMvc.perform(post("/api/migrations/execution/{executionId}/rollback", executionId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                    .andExpect(jsonPath("$.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.successfullyRolledBack").value(1))
                    .andExpect(jsonPath("$.failedRollbacks").value(0))
                    .andExpect(jsonPath("$.outcomes[0].destinationPlaylistItemId").value("yt_item_0"))
                    .andExpect(jsonPath("$.outcomes[0].status").value("ROLLED_BACK"));
        }

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("400 Bad Request: Anti-impersonation rejects caller-supplied userId mismatch on rollback")
        void rejectsCallerUserIdMismatchOnRollback() throws Exception {
            UUID wrongUserId = UUID.randomUUID();
            RollbackExecutionApiRequest request = new RollbackExecutionApiRequest(
                    wrongUserId, List.of("yt_item_0"), "Rollback impersonation");

            mockMvc.perform(post("/api/migrations/execution/{executionId}/rollback", executionId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verify(executionStateService, never()).rollbackExecution(any(), any());
        }

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("409 Conflict: Rejects rollback when execution is RUNNING")
        void rejectsRollbackWhenRunning() throws Exception {
            when(executionStateService.rollbackExecution(any(RollbackExecutionRequest.class), eq(userId)))
                    .thenThrow(new InvalidStateException("Cannot perform rollback while execution is in status RUNNING"));

            RollbackExecutionApiRequest request = new RollbackExecutionApiRequest(
                    userId, List.of("yt_item_0"), "Rollback running");

            mockMvc.perform(post("/api/migrations/execution/{executionId}/rollback", executionId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("GET /api/migrations/execution/{executionId}/rollbacks")
    class RollbackHistoryEndpoint {

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("200 OK: Retrieves rollback audit history for execution")
        void retrievesRollbackHistorySuccessfully() throws Exception {
            RollbackRecordDto record = new RollbackRecordDto(
                    UUID.randomUUID(),
                    executionId,
                    UUID.randomUUID(),
                    "yt_item_0",
                    RollbackRecordStatus.ROLLED_BACK,
                    null,
                    "User requested rollback",
                    Instant.now()
            );

            when(executionStateService.getRollbackHistory(executionId, userId))
                    .thenReturn(List.of(record));

            mockMvc.perform(get("/api/migrations/execution/{executionId}/rollbacks", executionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].executionId").value(executionId.toString()))
                    .andExpect(jsonPath("$[0].destinationPlaylistItemId").value("yt_item_0"))
                    .andExpect(jsonPath("$[0].status").value("ROLLED_BACK"))
                    .andExpect(jsonPath("$[0].reason").value("User requested rollback"));
        }

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("404 Not Found: Anti-IDOR when retrieving rollback history of another user's execution")
        void antiIdorReturns404ForRollbackHistory() throws Exception {
            when(executionStateService.getRollbackHistory(executionId, userId))
                    .thenThrow(new ResourceNotFoundException("MigrationExecution not found for ID: " + executionId));

            mockMvc.perform(get("/api/migrations/execution/{executionId}/rollbacks", executionId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @WithAnonymousUser
        @DisplayName("401 Unauthorized: Unauthenticated history request rejected")
        void unauthenticatedHistoryRejected() throws Exception {
            mockMvc.perform(get("/api/migrations/execution/{executionId}/rollbacks", executionId))
                    .andExpect(status().isUnauthorized());
        }
    }
}
