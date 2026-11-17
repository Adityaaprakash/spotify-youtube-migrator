package com.spotifyyoutube.migrator.migration.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.MigrationService;
import com.spotifyyoutube.migrator.migration.application.execution.ExecutionAuditService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.async.MigrationJobDispatcher;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionAuditEvent;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.progress.application.MigrationProgressService;
import com.spotifyyoutube.migrator.migration.application.execution.MigrationExecutionStateService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.progress.application.ProgressPollingRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller integration tests verifying migration history listing, detailed single-execution view,
 * chronological audit timelines, and strict anti-IDOR security controls (Phase 14C & 14D).
 */
@WebMvcTest(MigrationController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class MigrationHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MigrationService migrationService;

    @MockBean
    private MigrationJobDispatcher jobDispatcher;

    @MockBean
    private MigrationProgressService progressService;

    @MockBean
    private ProgressPollingRateLimiter rateLimiter;

    @MockBean
    private UserService userService;

    @MockBean
    private MigrationExecutionRepository executionRepository;

    @MockBean
    private ExecutionAuditService auditService;

    @MockBean
    private MigrationExecutionStateService executionStateService;

    @MockBean
    private ExecutionTrackRecordRepository trackRecordRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID userId;
    private User authenticatedUser;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        authenticatedUser = new User("user@example.com");
        ReflectionTestUtils.setField(authenticatedUser, "id", userId);

        when(userService.getUserByEmail("user@example.com")).thenReturn(authenticatedUser);
    }

    private MigrationExecution createMockExecution(UUID execId, User user, MigrationExecutionStatus executionStatus) {
        com.spotifyyoutube.migrator.migration.review.domain.ReviewSession reviewSession =
                new com.spotifyyoutube.migrator.migration.review.domain.ReviewSession(user, "spotify-pl-123", "Summer Vibes", UUID.randomUUID());
        ReflectionTestUtils.setField(reviewSession, "id", UUID.randomUUID());

        MigrationExecution execution = new MigrationExecution();
        ReflectionTestUtils.setField(execution, "id", execId);
        ReflectionTestUtils.setField(execution, "user", user);
        ReflectionTestUtils.setField(execution, "reviewSession", reviewSession);
        ReflectionTestUtils.setField(execution, "sourcePlaylistId", "spotify-pl-123");
        ReflectionTestUtils.setField(execution, "sourcePlaylistTitle", "Summer Vibes");
        ReflectionTestUtils.setField(execution, "destinationPlatform", com.spotifyyoutube.migrator.playlist.domain.Platform.YOUTUBE);
        ReflectionTestUtils.setField(execution, "destinationPlaylistId", "yt-pl-456");
        ReflectionTestUtils.setField(execution, "destinationPlaylistUrl", "https://youtube.com/playlist?list=yt-pl-456");
        ReflectionTestUtils.setField(execution, "status", executionStatus);
        ReflectionTestUtils.setField(execution, "totalSourceTracks", 10);
        ReflectionTestUtils.setField(execution, "matchedTracks", 10);
        ReflectionTestUtils.setField(execution, "totalApprovedTracks", 10);
        ReflectionTestUtils.setField(execution, "successfulInsertions", 8);
        ReflectionTestUtils.setField(execution, "failedInsertions", 2);
        ReflectionTestUtils.setField(execution, "createdAt", Instant.parse("2026-03-01T10:00:00Z"));
        ReflectionTestUtils.setField(execution, "updatedAt", Instant.parse("2026-03-01T10:05:00Z"));
        ReflectionTestUtils.setField(execution, "startedAt", Instant.parse("2026-03-01T10:00:30Z"));
        ReflectionTestUtils.setField(execution, "completedAt", Instant.parse("2026-03-01T10:05:00Z"));
        return execution;
    }

    @Nested
    @DisplayName("GET /api/migrations/history - Migration History Listing")
    class HistoryListingTests {

        @Test
        @WithMockUser(username = "user@example.com")
        @DisplayName("Returns paginated migration history for authenticated user")
        void getHistory_Success() throws Exception {
            UUID execId = UUID.randomUUID();
            MigrationExecution execution = createMockExecution(execId, authenticatedUser, MigrationExecutionStatus.COMPLETED);

            Page<MigrationExecution> page = new PageImpl<>(List.of(execution), PageRequest.of(0, 20), 1);
            when(executionRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/migrations/history")
                            .param("page", "0")
                            .param("size", "20")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].migrationId").value(execId.toString()))
                    .andExpect(jsonPath("$.content[0].sourcePlaylistTitle").value("Summer Vibes"))
                    .andExpect(jsonPath("$.content[0].status").value("COMPLETED"))
                    .andExpect(jsonPath("$.content[0].totalSourceTracks").value(10))
                    .andExpect(jsonPath("$.content[0].matchedTracks").value(10))
                    .andExpect(jsonPath("$.content[0].successfulInsertions").value(8))
                    .andExpect(jsonPath("$.content[0].failedInsertions").value(2));

            verify(executionRepository).findByUserIdOrderByCreatedAtDesc(eq(userId), any(Pageable.class));
        }

        @Test
        @WithMockUser(username = "user@example.com")
        @DisplayName("GET /api/migrations root path returns paginated migration history for authenticated user")
        void getHistory_RootPathSuccess() throws Exception {
            UUID execId = UUID.randomUUID();
            MigrationExecution execution = createMockExecution(execId, authenticatedUser, MigrationExecutionStatus.COMPLETED);

            Page<MigrationExecution> page = new PageImpl<>(List.of(execution), PageRequest.of(0, 20), 1);
            when(executionRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/migrations")
                            .param("page", "0")
                            .param("size", "20")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].migrationId").value(execId.toString()));
        }

        @Test
        @WithMockUser(username = "user@example.com")
        @DisplayName("Filters migration history by status when status parameter is supplied")
        void getHistory_WithStatusFilter() throws Exception {
            UUID execId = UUID.randomUUID();
            MigrationExecution execution = createMockExecution(execId, authenticatedUser, MigrationExecutionStatus.FAILED);

            Page<MigrationExecution> page = new PageImpl<>(List.of(execution), PageRequest.of(0, 20), 1);
            when(executionRepository.findByUserIdAndStatusOrderByCreatedAtDesc(
                    eq(userId), eq(MigrationExecutionStatus.FAILED), any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/migrations/history")
                            .param("status", "FAILED")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].status").value("FAILED"));

            verify(executionRepository).findByUserIdAndStatusOrderByCreatedAtDesc(
                    eq(userId), eq(MigrationExecutionStatus.FAILED), any(Pageable.class));
        }

        @Test
        @WithAnonymousUser
        @DisplayName("Unauthenticated request to history endpoint returns 403 Forbidden")
        void getHistory_UnauthenticatedReturnsForbidden() throws Exception {
            mockMvc.perform(get("/api/migrations/history")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(username = "user@example.com")
        @DisplayName("Invalid status filter returns 400 Bad Request")
        void getHistory_InvalidStatusReturnsBadRequest() throws Exception {
            mockMvc.perform(get("/api/migrations/history")
                            .param("status", "INVALID_STATUS")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Nested
    @DisplayName("GET /api/migrations/{id} - Detailed Single-Execution View")
    class SingleExecutionDetailTests {

        @Test
        @WithMockUser(username = "user@example.com")
        @DisplayName("Owner can retrieve detailed view of single migration execution with track records")
        void getExecution_OwnerSuccess() throws Exception {
            UUID execId = UUID.randomUUID();
            MigrationExecution execution = createMockExecution(execId, authenticatedUser, MigrationExecutionStatus.COMPLETED);

            ApprovedTrack approvedTrack = new ApprovedTrack(
                    0,
                    "sp-track-1",
                    "Song A",
                    "Artist X",
                    "yt-vid-1",
                    "Song A Official",
                    "Artist X Vevo",
                    java.time.Duration.ofSeconds(180),
                    com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource.AUTOMATIC,
                    0,
                    0.98,
                    "EXACT_ISRC"
            );
            ExecutionTrackRecord trackRecord = new ExecutionTrackRecord(execution, approvedTrack);
            ReflectionTestUtils.setField(trackRecord, "status", ExecutionTrackStatus.ADDED);
            ReflectionTestUtils.setField(trackRecord, "destinationPlaylistItemId", "yt-item-1");

            execution.getMutableTrackRecords().add(trackRecord);

            when(executionRepository.findById(execId)).thenReturn(Optional.of(execution));

            mockMvc.perform(get("/api/migrations/{id}", execId)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.executionId").value(execId.toString()))
                    .andExpect(jsonPath("$.sourcePlaylistTitle").value("Summer Vibes"))
                    .andExpect(jsonPath("$.destinationPlaylistId").value("yt-pl-456"))
                    .andExpect(jsonPath("$.destinationPlaylistUrl").value("https://youtube.com/playlist?list=yt-pl-456"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.totalSourceTracks").value(10))
                    .andExpect(jsonPath("$.matchedTracks").value(10))
                    .andExpect(jsonPath("$.trackRecords[0].sourceTitle").value("Song A"))
                    .andExpect(jsonPath("$.trackRecords[0].sourceArtist").value("Artist X"))
                    .andExpect(jsonPath("$.trackRecords[0].destinationTrackId").value("yt-vid-1"))
                    .andExpect(jsonPath("$.trackRecords[0].status").value("ADDED"))
                    .andExpect(jsonPath("$.trackRecords[0].matchScore").value(0.98))
                    .andExpect(jsonPath("$.trackRecords[0].matchDecision").value("EXACT_ISRC"));
        }

        @Test
        @WithMockUser(username = "user@example.com")
        @DisplayName("Anti-IDOR: Requesting another user's migration returns 404 NOT FOUND without disclosure")
        void getExecution_AntiIdorReturns404() throws Exception {
            UUID execId = UUID.randomUUID();
            User otherUser = new User("other@example.com");
            ReflectionTestUtils.setField(otherUser, "id", UUID.randomUUID());

            MigrationExecution execution = createMockExecution(execId, otherUser, MigrationExecutionStatus.COMPLETED);
            when(executionRepository.findById(execId)).thenReturn(Optional.of(execution));

            mockMvc.perform(get("/api/migrations/{id}", execId)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Migration execution not found: " + execId));
        }

        @Test
        @WithMockUser(username = "user@example.com")
        @DisplayName("Nonexistent migration ID returns 404 NOT FOUND")
        void getExecution_NonexistentReturns404() throws Exception {
            UUID unknownId = UUID.randomUUID();
            when(executionRepository.findById(unknownId)).thenReturn(Optional.empty());
            when(migrationService.getMigrationJobById(unknownId)).thenThrow(new ResourceNotFoundException("Migration not found"));

            mockMvc.perform(get("/api/migrations/{id}", unknownId)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @WithMockUser(username = "user@example.com")
        @DisplayName("Invalid UUID path variable returns 400 Bad Request")
        void getExecution_InvalidUuidReturns400() throws Exception {
            mockMvc.perform(get("/api/migrations/not-a-valid-uuid")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        @Test
        @WithAnonymousUser
        @DisplayName("Unauthenticated request to detail endpoint returns 403 Forbidden")
        void getExecution_UnauthenticatedReturnsForbidden() throws Exception {
            mockMvc.perform(get("/api/migrations/{id}", UUID.randomUUID())
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET /api/migrations/{id}/history - Chronological Audit Timeline")
    class AuditTimelineTests {

        @Test
        @WithMockUser(username = "user@example.com")
        @DisplayName("Owner can retrieve chronological audit timeline of migration execution")
        void getAuditHistory_OwnerSuccess() throws Exception {
            UUID execId = UUID.randomUUID();
            MigrationExecution execution = createMockExecution(execId, authenticatedUser, MigrationExecutionStatus.COMPLETED);

            ExecutionAuditEvent event1 = ExecutionAuditEvent.stateTransition(
                    execution, null, MigrationExecutionStatus.INITIALIZED, "Execution created", "USER", null);
            ReflectionTestUtils.setField(event1, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(event1, "createdAt", Instant.parse("2026-03-01T10:00:00Z"));

            ExecutionAuditEvent event2 = ExecutionAuditEvent.stateTransition(
                    execution, MigrationExecutionStatus.INITIALIZED, MigrationExecutionStatus.QUEUED, "Queued", "USER", null);
            ReflectionTestUtils.setField(event2, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(event2, "createdAt", Instant.parse("2026-03-01T10:00:05Z"));

            Page<ExecutionAuditEvent> page = new PageImpl<>(List.of(event1, event2), PageRequest.of(0, 50), 2);
            when(auditService.getAuditTimeline(eq(execId), eq(userId), any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/migrations/{id}/history", execId)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.content[0].eventType").value("STATE_TRANSITION"))
                    .andExpect(jsonPath("$.content[0].newState").value("INITIALIZED"))
                    .andExpect(jsonPath("$.content[0].reason").value("Execution created"))
                    .andExpect(jsonPath("$.content[1].eventType").value("STATE_TRANSITION"))
                    .andExpect(jsonPath("$.content[1].previousState").value("INITIALIZED"))
                    .andExpect(jsonPath("$.content[1].newState").value("QUEUED"));

            verify(auditService).getAuditTimeline(eq(execId), eq(userId), any(Pageable.class));
        }

        @Test
        @WithMockUser(username = "user@example.com")
        @DisplayName("Anti-IDOR: Requesting audit history for another user's execution returns 404 NOT FOUND")
        void getAuditHistory_AntiIdorReturns404() throws Exception {
            UUID execId = UUID.randomUUID();
            when(auditService.getAuditTimeline(eq(execId), eq(userId), any(Pageable.class)))
                    .thenThrow(new ResourceNotFoundException("Migration execution not found: " + execId));

            mockMvc.perform(get("/api/migrations/{id}/history", execId)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Migration execution not found: " + execId));
        }

        @Test
        @WithAnonymousUser
        @DisplayName("Unauthenticated request to audit history endpoint returns 403 Forbidden")
        void getAuditHistory_UnauthenticatedReturnsForbidden() throws Exception {
            mockMvc.perform(get("/api/migrations/{id}/history", UUID.randomUUID())
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET /api/migrations/{id}/tracks - Migration Track-Level Outcomes")
    class TrackOutcomesTests {

        @Test
        @WithMockUser(username = "user@example.com")
        @DisplayName("Owner can retrieve track execution outcomes in target position order")
        void getTracks_OwnerSuccess() throws Exception {
            UUID execId = UUID.randomUUID();
            MigrationExecution execution = createMockExecution(execId, authenticatedUser, MigrationExecutionStatus.COMPLETED);

            ExecutionTrackRecord track1 = new ExecutionTrackRecord(execution, 0, "sp-1", "yt-1", "Midnight City", "M83");
            ReflectionTestUtils.setField(track1, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(track1, "destinationTitle", "M83 - Midnight City");
            ReflectionTestUtils.setField(track1, "destinationChannel", "M83");
            ReflectionTestUtils.setField(track1, "destinationDurationMs", 243000L);
            ReflectionTestUtils.setField(track1, "matchScore", 0.98);
            ReflectionTestUtils.setField(track1, "matchDecision", "MATCH");
            ReflectionTestUtils.setField(track1, "decisionSource", "AUTOMATIC");
            ReflectionTestUtils.setField(track1, "status", ExecutionTrackStatus.ADDED);

            when(executionRepository.findById(execId)).thenReturn(Optional.of(execution));
            when(trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(execId)).thenReturn(List.of(track1));

            mockMvc.perform(get("/api/migrations/{id}/tracks", execId)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].sourceTitle").value("Midnight City"))
                    .andExpect(jsonPath("$[0].sourceArtist").value("M83"))
                    .andExpect(jsonPath("$[0].destinationTitle").value("M83 - Midnight City"))
                    .andExpect(jsonPath("$[0].destinationChannel").value("M83"))
                    .andExpect(jsonPath("$[0].status").value("ADDED"))
                    .andExpect(jsonPath("$[0].matchScore").value(0.98));
        }

        @Test
        @WithMockUser(username = "user@example.com")
        @DisplayName("Anti-IDOR: Requesting track outcomes for another user's execution returns 404 NOT FOUND")
        void getTracks_AntiIdorReturns404() throws Exception {
            UUID execId = UUID.randomUUID();
            User otherUser = new User("other@example.com");
            ReflectionTestUtils.setField(otherUser, "id", UUID.randomUUID());
            MigrationExecution execution = createMockExecution(execId, otherUser, MigrationExecutionStatus.COMPLETED);

            when(executionRepository.findById(execId)).thenReturn(Optional.of(execution));

            mockMvc.perform(get("/api/migrations/{id}/tracks", execId)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @WithMockUser(username = "user@example.com")
        @DisplayName("Requesting tracks for non-existent execution returns 404 NOT FOUND")
        void getTracks_NotFound() throws Exception {
            UUID execId = UUID.randomUUID();
            when(executionRepository.findById(execId)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/migrations/{id}/tracks", execId)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @WithAnonymousUser
        @DisplayName("Unauthenticated request to tracks endpoint returns 403 Forbidden")
        void getTracks_UnauthenticatedReturnsForbidden() throws Exception {
            mockMvc.perform(get("/api/migrations/{id}/tracks", UUID.randomUUID())
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("POST /api/migrations/{id}/resume - Safe Migration Resumption")
    class MigrationResumeTests {

        @Test
        @WithMockUser(username = "user@example.com")
        @DisplayName("Owner can resume eligible execution with pending tracks")
        void resume_EligiblePendingTracksSuccess() throws Exception {
            UUID execId = UUID.randomUUID();
            MigrationExecution execution = createMockExecution(execId, authenticatedUser, MigrationExecutionStatus.PARTIALLY_COMPLETED);

            ExecutionTrackRecord t0 = new ExecutionTrackRecord(execution, 0, "sp-0", "yt-0", "Song A", "Artist A");
            ReflectionTestUtils.setField(t0, "status", ExecutionTrackStatus.ADDED);
            ExecutionTrackRecord t1 = new ExecutionTrackRecord(execution, 1, "sp-1", "yt-1", "Song B", "Artist B");
            ReflectionTestUtils.setField(t1, "status", ExecutionTrackStatus.PENDING);
            ReflectionTestUtils.setField(execution, "trackRecords", List.of(t0, t1));

            MigrationExecution completedExec = createMockExecution(execId, authenticatedUser, MigrationExecutionStatus.COMPLETED);
            when(executionRepository.findById(execId)).thenReturn(Optional.of(execution));
            when(executionStateService.resumeExecution(eq(execId), eq(userId))).thenReturn(MigrationExecutionSummary.from(completedExec));

            mockMvc.perform(post("/api/migrations/{id}/resume", execId)
                            .with(csrf())
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"));

            verify(executionStateService).resumeExecution(eq(execId), eq(userId));
        }

        @Test
        @WithMockUser(username = "user@example.com")
        @DisplayName("Owner can resume and recover execution with failed/skipped tracks")
        void resume_RecoverFailedTracksSuccess() throws Exception {
            UUID execId = UUID.randomUUID();
            MigrationExecution execution = createMockExecution(execId, authenticatedUser, MigrationExecutionStatus.FAILED);

            ExecutionTrackRecord t0 = new ExecutionTrackRecord(execution, 0, "sp-0", "yt-0", "Song A", "Artist A");
            ReflectionTestUtils.setField(t0, "status", ExecutionTrackStatus.FAILED);
            ReflectionTestUtils.setField(execution, "trackRecords", List.of(t0));

            MigrationExecution completedExec = createMockExecution(execId, authenticatedUser, MigrationExecutionStatus.COMPLETED);
            when(executionRepository.findById(execId)).thenReturn(Optional.of(execution));
            when(executionStateService.recoverExecution(eq(execId), eq(userId))).thenReturn(MigrationExecutionSummary.from(completedExec));

            mockMvc.perform(post("/api/migrations/{id}/resume", execId)
                            .with(csrf())
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"));

            verify(executionStateService).recoverExecution(eq(execId), eq(userId));
        }

        @Test
        @WithMockUser(username = "user@example.com")
        @DisplayName("Resume rejects when execution is already RUNNING (409 Conflict)")
        void resume_ConflictWhenRunning() throws Exception {
            UUID execId = UUID.randomUUID();
            MigrationExecution execution = createMockExecution(execId, authenticatedUser, MigrationExecutionStatus.RUNNING);

            when(executionRepository.findById(execId)).thenReturn(Optional.of(execution));

            mockMvc.perform(post("/api/migrations/{id}/resume", execId)
                            .with(csrf())
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                    .andExpect(jsonPath("$.message").value("Migration execution is already in progress"));
        }

        @Test
        @WithMockUser(username = "user@example.com")
        @DisplayName("Resume rejects when execution is already COMPLETED (409 Conflict)")
        void resume_ConflictWhenCompleted() throws Exception {
            UUID execId = UUID.randomUUID();
            MigrationExecution execution = createMockExecution(execId, authenticatedUser, MigrationExecutionStatus.COMPLETED);

            when(executionRepository.findById(execId)).thenReturn(Optional.of(execution));

            mockMvc.perform(post("/api/migrations/{id}/resume", execId)
                            .with(csrf())
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                    .andExpect(jsonPath("$.message").value("Cannot resume: execution is already COMPLETED"));
        }

        @Test
        @WithMockUser(username = "user@example.com")
        @DisplayName("Resume rejects when execution contains UNCERTAIN tracks (409 Conflict)")
        void resume_ConflictWhenUncertain() throws Exception {
            UUID execId = UUID.randomUUID();
            MigrationExecution execution = createMockExecution(execId, authenticatedUser, MigrationExecutionStatus.RECOVERY_REQUIRED);
            ReflectionTestUtils.setField(execution, "uncertainInsertions", 1);

            when(executionRepository.findById(execId)).thenReturn(Optional.of(execution));

            mockMvc.perform(post("/api/migrations/{id}/resume", execId)
                            .with(csrf())
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                    .andExpect(jsonPath("$.message").value("Cannot resume: execution contains uncertain tracks that require reconciliation"));
        }

        @Test
        @WithMockUser(username = "user@example.com")
        @DisplayName("Anti-IDOR: Resuming another user's execution returns 404 NOT FOUND")
        void resume_AntiIdorReturns404() throws Exception {
            UUID execId = UUID.randomUUID();
            User otherUser = new User("other@example.com");
            ReflectionTestUtils.setField(otherUser, "id", UUID.randomUUID());
            MigrationExecution execution = createMockExecution(execId, otherUser, MigrationExecutionStatus.PARTIALLY_COMPLETED);

            when(executionRepository.findById(execId)).thenReturn(Optional.of(execution));

            mockMvc.perform(post("/api/migrations/{id}/resume", execId)
                            .with(csrf())
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @WithAnonymousUser
        @DisplayName("Unauthenticated request to resume endpoint returns 403 Forbidden")
        void resume_UnauthenticatedReturnsForbidden() throws Exception {
            mockMvc.perform(post("/api/migrations/{id}/resume", UUID.randomUUID())
                            .with(csrf())
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());
        }
    }
}
