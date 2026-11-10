package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.config.SecurityConfig;
import com.spotifyyoutube.migrator.common.exception.ErrorCode;
import com.spotifyyoutube.migrator.common.exception.GlobalExceptionHandler;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.api.MigrationController;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationSubmissionResponse;
import com.spotifyyoutube.migrator.migration.api.dto.RunMigrationApiRequest;
import com.spotifyyoutube.migrator.migration.application.MigrationService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.async.MigrationJobDispatcher;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.progress.application.MigrationProgressService;
import com.spotifyyoutube.migrator.migration.progress.application.ProgressPollingRateLimiter;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MigrationController.class)
@Import({
        SecurityConfig.class,
        GlobalExceptionHandler.class,
        ApiIdempotencyFilter.class,
        CanonicalRequestFingerprinter.class
})
@WithMockUser(username = "migrator-user@test.com")
@DisplayName("MigrationIdempotencyApiTest (Phase 13E End-to-End API Contracts)")
class MigrationIdempotencyApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    private MigrationIdempotencyService idempotencyService;

    @MockBean
    private ReviewSessionRepository reviewSessionRepository;

    private UUID userId;
    private User testUser;
    private UUID reviewSessionId;

    @BeforeEach
    void setUp() {
        userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        testUser = new User("migrator-user@test.com");
        testUser.setId(userId);
        reviewSessionId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        when(userService.getUserByEmail(testUser.getEmail())).thenReturn(testUser);
        when(userService.getUserById(userId)).thenReturn(testUser);
    }

    @Test
    @DisplayName("POST /api/migrations/submit requires Idempotency-Key header; returns 400 Bad Request when missing")
    void typedSubmit_missingHeader_returnsBadRequest() throws Exception {
        RunMigrationApiRequest req = new RunMigrationApiRequest(reviewSessionId, userId, null, "Target", null, true);

        mockMvc.perform(post("/api/migrations/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.name()))
                .andExpect(jsonPath("$.message")
                        .value("Idempotency-Key header is required for /api/migrations/submit"));
    }

    @Test
    @DisplayName("POST /api/migrations/submit with oversized key (> 255 chars) returns 400 Bad Request")
    void typedSubmit_oversizedKey_returnsBadRequest() throws Exception {
        RunMigrationApiRequest req = new RunMigrationApiRequest(reviewSessionId, userId, null, "Target", null, true);

        mockMvc.perform(post("/api/migrations/submit")
                        .with(csrf())
                        .header("Idempotency-Key", "x".repeat(256))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.name()))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("exceeds maximum length")));
    }

    @Test
    @DisplayName("POST /api/migrations allows submission without key (backward compatibility)")
    void legacyEndpoint_missingKey_succeeds() throws Exception {
        UUID executionId = UUID.randomUUID();
        MigrationExecutionSummary summary = new MigrationExecutionSummary(
                executionId, reviewSessionId, MigrationExecutionStatus.QUEUED,
                "dest-pl-1", "https://youtube.com/playlist?list=dest-pl-1",
                10, 0, 0, 0, 0, 0, null, null
        );

        when(jobDispatcher.dispatchMigration(any(), eq(userId))).thenReturn(summary);

        String json = "{\"reviewSessionId\":\"" + reviewSessionId + "\"}";

        mockMvc.perform(post("/api/migrations")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    @DisplayName("POST /api/migrations/submit first request dispatches migration and records idempotency")
    void typedSubmit_validFirstRequest_dispatchesAndRecords() throws Exception {
        UUID executionId = UUID.randomUUID();
        String key = "req-key-001";

        when(idempotencyService.findExistingRecord(eq(userId), eq(key)))
                .thenReturn(Optional.empty());

        MigrationExecutionSummary summary = new MigrationExecutionSummary(
                executionId, reviewSessionId, MigrationExecutionStatus.QUEUED,
                "dest-pl-1", "https://youtube.com/playlist?list=dest-pl-1",
                15, 0, 0, 0, 0, 0, null, null
        );
        when(jobDispatcher.dispatchMigration(any(), eq(userId))).thenReturn(summary);

        RunMigrationApiRequest req = new RunMigrationApiRequest(reviewSessionId, userId, null, "Target", null, true);

        mockMvc.perform(post("/api/migrations/submit")
                        .with(csrf())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/migrations/" + executionId + "/progress"))
                .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"));

        verify(jobDispatcher).dispatchMigration(any(), eq(userId));
        verify(idempotencyService).recordSubmission(
                eq(userId), eq(key), any(), eq(executionId), eq(202), any()
        );
    }

    @Test
    @DisplayName("POST /api/migrations/submit replay returns cached response without calling dispatcher")
    void typedSubmit_matchingReplay_returnsCachedResponseWithoutCallingDispatcher() throws Exception {
        UUID originalExecId = UUID.randomUUID();
        String key = "req-key-replay-002";

        // Calculate expected fingerprint for this request
        CanonicalRequestFingerprinter fpCalculator = new CanonicalRequestFingerprinter();
        CanonicalMigrationRequest canonicalReq = new CanonicalMigrationRequest(
                userId, reviewSessionId.toString(), "Target Playlist", java.util.List.of(reviewSessionId.toString())
        );
        String expectedFingerprint = fpCalculator.calculateFingerprint(canonicalReq);

        MigrationSubmissionResponse cachedResponse = new MigrationSubmissionResponse(
                originalExecId, reviewSessionId, MigrationExecutionStatus.QUEUED,
                "/api/migrations/" + originalExecId + "/progress",
                "Migration request accepted and queued for background processing",
                15, java.time.Instant.now()
        );
        String cachedPayload = objectMapper.writeValueAsString(cachedResponse);

        MigrationIdempotencyRecord record = new MigrationIdempotencyRecord(
                userId, key, expectedFingerprint, originalExecId, 202, cachedPayload, null, null
        );

        when(idempotencyService.findExistingRecord(eq(userId), eq(key)))
                .thenReturn(Optional.of(record));

        RunMigrationApiRequest req = new RunMigrationApiRequest(
                reviewSessionId, userId, null, "Target Playlist", null, true
        );

        mockMvc.perform(post("/api/migrations/submit")
                        .with(csrf())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/migrations/" + originalExecId + "/progress"))
                .andExpect(jsonPath("$.executionId").value(originalExecId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"));

        // Dispatcher must NEVER be called on replay!
        verify(jobDispatcher, never()).dispatchMigration(any(), any());
    }

    @Test
    @DisplayName("POST /api/migrations/submit with differing payload returns 409 IDEMPOTENCY_FINGERPRINT_MISMATCH")
    void typedSubmit_fingerprintMismatch_returns409Conflict() throws Exception {
        UUID originalExecId = UUID.randomUUID();
        String key = "req-key-conflict-003";

        MigrationIdempotencyRecord record = new MigrationIdempotencyRecord(
                userId, key, "v1:initialOriginalFingerprint", originalExecId, 202, "{}", null, null
        );

        when(idempotencyService.findExistingRecord(eq(userId), eq(key)))
                .thenReturn(Optional.of(record));

        RunMigrationApiRequest modifiedReq = new RunMigrationApiRequest(
                reviewSessionId, userId, null, "Completely Different Title", null, true
        );

        mockMvc.perform(post("/api/migrations/submit")
                        .with(csrf())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(modifiedReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.IDEMPOTENCY_FINGERPRINT_MISMATCH.name()))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("previously used with a different request payload")
                ));

        verify(jobDispatcher, never()).dispatchMigration(any(), any());
    }
}
