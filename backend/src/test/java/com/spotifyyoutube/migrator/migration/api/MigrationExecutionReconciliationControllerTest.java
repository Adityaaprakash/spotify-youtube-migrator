package com.spotifyyoutube.migrator.migration.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.api.dto.ReconcileExecutionApiRequest;
import com.spotifyyoutube.migrator.migration.application.execution.ApprovedMigrationExecutionService;
import com.spotifyyoutube.migrator.migration.application.execution.DestinationPlaylistCreationService;
import com.spotifyyoutube.migrator.migration.application.execution.MigrationExecutionStateService;
import com.spotifyyoutube.migrator.migration.application.execution.OrderedTrackInsertionService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionReconciliationStatusDto;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionReconciliationSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ReconcileExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ReconciliationRecordDto;
import com.spotifyyoutube.migrator.migration.application.execution.contract.TrackReconciliationOutcomeDto;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.ReconciliationOutcome;
import com.spotifyyoutube.migrator.migration.execution.domain.ReconciliationType;
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
@DisplayName("Phase 11G: Migration Execution Reconciliation Controller WebMvc Test")
class MigrationExecutionReconciliationControllerTest {

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
    private UUID trackId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        testUser = new User("tester@example.com");
        testUser.setId(userId);
        executionId = UUID.randomUUID();
        trackId = UUID.randomUUID();

        when(userService.getUserByEmail("tester@example.com")).thenReturn(testUser);
        when(userService.getUserById(userId)).thenReturn(testUser);
    }

    @Nested
    @DisplayName("POST /api/migrations/execution/{executionId}/reconcile")
    class ReconcileEndpoint {

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("200 OK: Successfully reconciles eligible uncertain tracks")
        void reconcilesSuccessfully() throws Exception {
            TrackReconciliationOutcomeDto outcomeDto = new TrackReconciliationOutcomeDto(
                    trackId,
                    "spotify_t1",
                    "yt_video_1",
                    ReconciliationType.INSERTION,
                    ReconciliationOutcome.CONFIRMED_INSERTED,
                    ExecutionTrackStatus.UNCERTAIN,
                    ExecutionTrackStatus.ADDED,
                    "yt_item_1",
                    "Verified remote playlist item inserted after start timestamp",
                    null
            );

            ExecutionReconciliationSummary summary = new ExecutionReconciliationSummary(
                    executionId,
                    userId,
                    "yt_pl_target",
                    MigrationExecutionStatus.COMPLETED,
                    1,
                    1,
                    0,
                    0,
                    0,
                    List.of(outcomeDto),
                    Instant.now()
            );

            when(executionStateService.reconcileExecution(eq(executionId), any(ReconcileExecutionRequest.class), eq(userId)))
                    .thenReturn(summary);

            ReconcileExecutionApiRequest request = new ReconcileExecutionApiRequest(null, null, userId);

            mockMvc.perform(post("/api/migrations/execution/{executionId}/reconcile", executionId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                    .andExpect(jsonPath("$.destinationPlaylistId").value("yt_pl_target"))
                    .andExpect(jsonPath("$.resultingExecutionStatus").value("COMPLETED"))
                    .andExpect(jsonPath("$.reconciledCount").value(1))
                    .andExpect(jsonPath("$.confirmedInsertedCount").value(1))
                    .andExpect(jsonPath("$.ambiguousCount").value(0))
                    .andExpect(jsonPath("$.outcomes[0].trackRecordId").value(trackId.toString()))
                    .andExpect(jsonPath("$.outcomes[0].outcome").value("CONFIRMED_INSERTED"))
                    .andExpect(jsonPath("$.outcomes[0].resolvedTrackStatus").value("ADDED"))
                    .andExpect(jsonPath("$.outcomes[0].resolvedPlaylistItemId").value("yt_item_1"));
        }

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("400 Bad Request: Anti-impersonation rejects caller-supplied userId mismatch")
        void rejectsCallerUserIdMismatch() throws Exception {
            UUID differentUserId = UUID.randomUUID();
            ReconcileExecutionApiRequest request = new ReconcileExecutionApiRequest(null, null, differentUserId);

            mockMvc.perform(post("/api/migrations/execution/{executionId}/reconcile", executionId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verify(executionStateService, never()).reconcileExecution(any(), any(), any());
        }

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("404 Not Found: Anti-IDOR when execution belongs to another user")
        void antiIdorReturns404() throws Exception {
            when(executionStateService.reconcileExecution(eq(executionId), any(ReconcileExecutionRequest.class), eq(userId)))
                    .thenThrow(new ResourceNotFoundException("Migration execution not found: " + executionId));

            mockMvc.perform(post("/api/migrations/execution/{executionId}/reconcile", executionId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound());
        }

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("409 Conflict: Rejects reconciliation when execution is in an illegal state")
        void conflictOnIllegalState() throws Exception {
            when(executionStateService.reconcileExecution(eq(executionId), any(ReconcileExecutionRequest.class), eq(userId)))
                    .thenThrow(new InvalidStateException("Cannot reconcile execution: execution is currently active or locked"));

            mockMvc.perform(post("/api/migrations/execution/{executionId}/reconcile", executionId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isConflict());
        }

        @Test
        @WithAnonymousUser
        @DisplayName("401 Unauthorized: Unauthenticated reconciliation rejected")
        void unauthenticatedReconciliationRejected() throws Exception {
            mockMvc.perform(post("/api/migrations/execution/{executionId}/reconcile", executionId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/migrations/execution/{executionId}/reconciliations")
    class ReconciliationHistoryEndpoint {

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("200 OK: Successfully retrieves reconciliation history")
        void retrievesHistorySuccessfully() throws Exception {
            UUID recordId = UUID.randomUUID();
            ReconciliationRecordDto record = new ReconciliationRecordDto(
                    recordId,
                    executionId,
                    trackId,
                    ReconciliationType.DELETION,
                    ExecutionTrackStatus.UNCERTAIN,
                    ExecutionTrackStatus.ROLLED_BACK,
                    ReconciliationOutcome.CONFIRMED_DELETED,
                    "yt_pl_target",
                    "yt_item_del",
                    "yt_vid_del",
                    "Exact item absent from complete inspection",
                    Instant.now(),
                    Instant.now().minusSeconds(2),
                    Instant.now(),
                    null,
                    null,
                    Instant.now()
            );

            when(executionStateService.getReconciliationHistory(executionId, userId))
                    .thenReturn(List.of(record));

            mockMvc.perform(get("/api/migrations/execution/{executionId}/reconciliations", executionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(recordId.toString()))
                    .andExpect(jsonPath("$[0].executionId").value(executionId.toString()))
                    .andExpect(jsonPath("$[0].reconciliationType").value("DELETION"))
                    .andExpect(jsonPath("$[0].reconciliationOutcome").value("CONFIRMED_DELETED"))
                    .andExpect(jsonPath("$[0].resolvedTrackStatus").value("ROLLED_BACK"));
        }

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("200 OK: Successfully retrieves reconciliation history via alias URL")
        void retrievesHistoryViaAliasSuccessfully() throws Exception {
            when(executionStateService.getReconciliationHistory(executionId, userId))
                    .thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/migrations/execution/{executionId}/reconciliation-history", executionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("404 Not Found: Anti-IDOR when retrieving reconciliation history")
        void historyAntiIdorReturns404() throws Exception {
            when(executionStateService.getReconciliationHistory(executionId, userId))
                    .thenThrow(new ResourceNotFoundException("Migration execution not found: " + executionId));

            mockMvc.perform(get("/api/migrations/execution/{executionId}/reconciliations", executionId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @WithAnonymousUser
        @DisplayName("401 Unauthorized: Unauthenticated history query rejected")
        void unauthenticatedHistoryRejected() throws Exception {
            mockMvc.perform(get("/api/migrations/execution/{executionId}/reconciliations", executionId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/migrations/execution/{executionId}/reconciliation-status")
    class ReconciliationStatusEndpoint {

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("200 OK: Successfully retrieves reconciliation status")
        void retrievesStatusSuccessfully() throws Exception {
            ExecutionReconciliationStatusDto statusDto = new ExecutionReconciliationStatusDto(
                    executionId,
                    userId,
                    MigrationExecutionStatus.RECOVERY_REQUIRED,
                    "yt_pl_target",
                    true,
                    1,
                    0,
                    1,
                    List.of(trackId)
            );

            when(executionStateService.getReconciliationStatus(executionId, userId))
                    .thenReturn(statusDto);

            mockMvc.perform(get("/api/migrations/execution/{executionId}/reconciliation-status", executionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                    .andExpect(jsonPath("$.executionStatus").value("RECOVERY_REQUIRED"))
                    .andExpect(jsonPath("$.reconciliationRequired").value(true))
                    .andExpect(jsonPath("$.uncertainTrackCount").value(1))
                    .andExpect(jsonPath("$.eligibleTrackIds[0]").value(trackId.toString()));
        }

        @Test
        @WithMockUser(username = "tester@example.com")
        @DisplayName("404 Not Found: Anti-IDOR when retrieving reconciliation status")
        void statusAntiIdorReturns404() throws Exception {
            when(executionStateService.getReconciliationStatus(executionId, userId))
                    .thenThrow(new ResourceNotFoundException("Migration execution not found: " + executionId));

            mockMvc.perform(get("/api/migrations/execution/{executionId}/reconciliation-status", executionId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @WithAnonymousUser
        @DisplayName("401 Unauthorized: Unauthenticated status query rejected")
        void unauthenticatedStatusRejected() throws Exception {
            mockMvc.perform(get("/api/migrations/execution/{executionId}/reconciliation-status", executionId))
                    .andExpect(status().isUnauthorized());
        }
    }
}
