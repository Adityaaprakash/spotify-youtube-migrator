package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.exception.ErrorCode;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiIdempotencyFilter Unit Tests (Phase 13E)")
class ApiIdempotencyFilterTest {

    @Mock
    private MigrationIdempotencyService idempotencyService;

    @Mock
    private CanonicalRequestFingerprinter fingerprinter;

    @Mock
    private ReviewSessionRepository reviewSessionRepository;

    @Mock
    private UserService userService;

    private ObjectMapper objectMapper;
    private ApiIdempotencyFilter filter;

    private UUID userId;
    private User testUser;
    private String idempotencyKey;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        filter = new ApiIdempotencyFilter(
                idempotencyService,
                fingerprinter,
                reviewSessionRepository,
                userService,
                objectMapper
        );

        userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        testUser = new User("user@example.com");
        testUser.setId(userId);
        idempotencyKey = "client-tx-1001";

        // Establish authenticated security context
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(testUser.getEmail(), "N/A", List.of())
        );
    }

    @Test
    @DisplayName("Filter does not intercept GET requests or unrelated POST paths")
    void shouldNotFilter_unrelatedEndpoints() {
        MockHttpServletRequest getReq = new MockHttpServletRequest("GET", "/api/migrations/123/progress");
        assertThat(filter.shouldNotFilter(getReq)).isTrue();

        MockHttpServletRequest validateReq = new MockHttpServletRequest("POST", "/api/migrations/execution/validate");
        assertThat(filter.shouldNotFilter(validateReq)).isTrue();

        MockHttpServletRequest legacyReq = new MockHttpServletRequest("POST", "/api/migrations");
        assertThat(filter.shouldNotFilter(legacyReq)).isFalse();

        MockHttpServletRequest submitReq = new MockHttpServletRequest("POST", "/api/migrations/submit");
        assertThat(filter.shouldNotFilter(submitReq)).isFalse();
    }

    @Test
    @DisplayName("Missing Idempotency-Key on typed endpoint /api/migrations/submit returns 400 Bad Request")
    void typedEndpoint_missingKey_returnsBadRequest() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/migrations/submit");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getContentAsString()).contains("Idempotency-Key header is required");
        assertThat(response.getContentAsString()).contains(ErrorCode.INVALID_REQUEST.name());
    }

    @Test
    @DisplayName("Missing Idempotency-Key on legacy /api/migrations is permitted for backward compatibility")
    void legacyEndpoint_missingKey_proceedsThroughFilterChain() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/migrations");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(filterChain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("Blank or whitespace-only key returns 400 Bad Request")
    void blankKey_returnsBadRequest() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/migrations/submit");
        request.addHeader("Idempotency-Key", "    ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getContentAsString()).contains("must not be blank");
    }

    @Test
    @DisplayName("Oversized key (> 255 characters) returns 400 Bad Request")
    void oversizedKey_returnsBadRequest() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/migrations/submit");
        request.addHeader("Idempotency-Key", "k".repeat(256));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getContentAsString()).contains("exceeds maximum length of 255 characters");
    }

    @Test
    @DisplayName("Leader acquires claim, executes downstream, and completes claim")
    void leaderAcquiresClaim_executesDownstreamAndCompletesClaim() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/migrations/submit");
        request.addHeader("Idempotency-Key", idempotencyKey);
        String requestJson = "{\"reviewSessionId\":\"12345678-1234-1234-1234-123456789abc\"}";
        request.setContent(requestJson.getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");

        MockHttpServletResponse response = new MockHttpServletResponse();

        when(userService.getUserByEmail(testUser.getEmail())).thenReturn(testUser);
        when(fingerprinter.resolveFromPayload(any(), any(), any()))
                .thenReturn(new CanonicalMigrationRequest(userId, "src", "target", List.of()));
        when(fingerprinter.calculateFingerprint(any(CanonicalMigrationRequest.class)))
                .thenReturn("v1:calculatedhash123");

        MigrationIdempotencyRecord pendingRecord = MigrationIdempotencyRecord.createPending(
                userId, idempotencyKey, "v1:calculatedhash123",
                UUID.fromString("12345678-1234-1234-1234-123456789abc"), null
        );
        when(idempotencyService.acquireClaim(eq(userId), eq(idempotencyKey), eq("v1:calculatedhash123"), any()))
                .thenReturn(ClaimResult.acquired(pendingRecord));

        UUID executionId = UUID.randomUUID();
        FilterChain chain = (req, res) -> {
            byte[] bytes = req.getInputStream().readAllBytes();
            assertThat(bytes).isNotEmpty();

            res.setStatus(HttpStatus.ACCEPTED.value());
            res.setContentType("application/json");
            res.getWriter().write("{\"executionId\":\"" + executionId + "\",\"status\":\"QUEUED\"}");
        };

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.ACCEPTED.value());
        verify(idempotencyService).completeClaim(
                eq(userId), eq(idempotencyKey), any(), anyLong(), eq(executionId), eq(202), any(),
                eq("/api/migrations/" + executionId + "/progress")
        );
    }

    @Test
    @DisplayName("Matching replay returns persisted status and payload without invoking downstream chain")
    void matchingReplay_returnsPersistedResponseWithoutInvokingDownstream() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/migrations/submit");
        request.addHeader("Idempotency-Key", idempotencyKey);
        request.setContent(
                "{\"reviewSessionId\":\"12345678-1234-1234-1234-123456789abc\"}".getBytes(StandardCharsets.UTF_8)
        );
        request.setContentType("application/json");

        MockHttpServletResponse response = new MockHttpServletResponse();

        String expectedFingerprint = "v1:exactFingerprintMatch";
        when(userService.getUserByEmail(testUser.getEmail())).thenReturn(testUser);
        when(fingerprinter.resolveFromPayload(any(), any(), any()))
                .thenReturn(new CanonicalMigrationRequest(userId, "src", "target", List.of()));
        when(fingerprinter.calculateFingerprint(any(CanonicalMigrationRequest.class)))
                .thenReturn(expectedFingerprint);

        UUID originalExecId = UUID.randomUUID();
        String originalPayload = "{\"executionId\":\"" + originalExecId
                + "\",\"status\":\"QUEUED\",\"progressUrl\":\"/api/migrations/" + originalExecId + "/progress\"}";

        MigrationIdempotencyRecord existing = new MigrationIdempotencyRecord(
                userId, idempotencyKey, expectedFingerprint, IdempotencyClaimStatus.COMPLETED,
                originalExecId, 202, originalPayload, "/api/migrations/" + originalExecId + "/progress",
                null, null, null, null
        );

        when(idempotencyService.acquireClaim(eq(userId), eq(idempotencyKey), eq(expectedFingerprint), any()))
                .thenReturn(ClaimResult.replay(existing));

        FilterChain chain = (req, res) -> {
            throw new AssertionError("Downstream filter chain must NOT be invoked on idempotency replay!");
        };

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.ACCEPTED.value());
        assertThat(response.getContentAsString()).isEqualTo(originalPayload);
        assertThat(response.getHeader(HttpHeaders.LOCATION))
                .isEqualTo("/api/migrations/" + originalExecId + "/progress");
    }

    @Test
    @DisplayName("Key with different fingerprint returns HTTP 409 Conflict with IDEMPOTENCY_FINGERPRINT_MISMATCH")
    void differentFingerprint_returns409Conflict() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/migrations/submit");
        request.addHeader("Idempotency-Key", idempotencyKey);
        request.setContent("{\"targetPlaylistTitle\":\"Changed Title\"}".getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");

        MockHttpServletResponse response = new MockHttpServletResponse();

        when(userService.getUserByEmail(testUser.getEmail())).thenReturn(testUser);
        when(fingerprinter.resolveFromPayload(any(), any(), any()))
                .thenReturn(new CanonicalMigrationRequest(userId, "src", "Changed Title", List.of()));
        when(fingerprinter.calculateFingerprint(any(CanonicalMigrationRequest.class)))
                .thenReturn("v1:newFingerprintChanged");

        when(idempotencyService.acquireClaim(eq(userId), eq(idempotencyKey), eq("v1:newFingerprintChanged"), any()))
                .thenReturn(ClaimResult.mismatch(String.format(
                        "Idempotency key '%s' was previously used with a different request payload", idempotencyKey
                )));

        FilterChain chain = (req, res) -> {
            throw new AssertionError("Downstream filter chain must NOT be invoked on fingerprint conflict!");
        };

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(response.getContentAsString()).contains(ErrorCode.IDEMPOTENCY_FINGERPRINT_MISMATCH.name());
        assertThat(response.getContentAsString()).contains("previously used with a different request payload");
    }

    @Test
    @DisplayName("Concurrent follower polls and replays winning record when completed")
    void concurrentFollower_pollsAndReplaysCompletedRecord() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/migrations/submit");
        request.addHeader("Idempotency-Key", idempotencyKey);
        request.setContent("{\"reviewSessionId\":\"12345678-1234-1234-1234-123456789abc\"}".getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");

        MockHttpServletResponse response = new MockHttpServletResponse();

        String expectedFingerprint = "v1:concurrentMatchFingerprint";
        when(userService.getUserByEmail(testUser.getEmail())).thenReturn(testUser);
        when(fingerprinter.resolveFromPayload(any(), any(), any()))
                .thenReturn(new CanonicalMigrationRequest(userId, "src", "target", List.of()));
        when(fingerprinter.calculateFingerprint(any(CanonicalMigrationRequest.class)))
                .thenReturn(expectedFingerprint);

        UUID winningExecId = UUID.randomUUID();
        String winningPayload = "{\"executionId\":\"" + winningExecId + "\",\"status\":\"QUEUED\"}";
        MigrationIdempotencyRecord pendingRecord = MigrationIdempotencyRecord.createPending(
                userId, idempotencyKey, expectedFingerprint, null, null
        );
        MigrationIdempotencyRecord completedRecord = new MigrationIdempotencyRecord(
                userId, idempotencyKey, expectedFingerprint, IdempotencyClaimStatus.COMPLETED,
                winningExecId, 202, winningPayload, "/api/migrations/" + winningExecId + "/progress",
                null, null, null, null
        );

        when(idempotencyService.acquireClaim(eq(userId), eq(idempotencyKey), eq(expectedFingerprint), any()))
                .thenReturn(ClaimResult.pending(pendingRecord));

        when(idempotencyService.findExistingRecord(userId, idempotencyKey))
                .thenReturn(Optional.of(completedRecord));

        FilterChain chain = (req, res) -> {
            throw new AssertionError("Downstream filter chain must NOT be invoked for follower request!");
        };

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.ACCEPTED.value());
        assertThat(response.getContentAsString()).isEqualTo(winningPayload);
    }

    @Test
    @DisplayName("Downstream exception releases claim and propagates exception")
    void downstreamException_releasesClaimAndPropagates() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/migrations/submit");
        request.addHeader("Idempotency-Key", idempotencyKey);
        request.setContent("{\"reviewSessionId\":\"12345678-1234-1234-1234-123456789abc\"}".getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");

        MockHttpServletResponse response = new MockHttpServletResponse();

        when(userService.getUserByEmail(testUser.getEmail())).thenReturn(testUser);
        when(fingerprinter.resolveFromPayload(any(), any(), any()))
                .thenReturn(new CanonicalMigrationRequest(userId, "src", "target", List.of()));
        when(fingerprinter.calculateFingerprint(any(CanonicalMigrationRequest.class)))
                .thenReturn("v1:calculatedhash123");

        MigrationIdempotencyRecord pendingRecord = MigrationIdempotencyRecord.createPending(
                userId, idempotencyKey, "v1:calculatedhash123", null, null
        );
        when(idempotencyService.acquireClaim(eq(userId), eq(idempotencyKey), eq("v1:calculatedhash123"), any()))
                .thenReturn(ClaimResult.acquired(pendingRecord));

        FilterChain chain = (req, res) -> {
            throw new RuntimeException("Simulated downstream database failure");
        };

        assertThatThrownBy(() -> filter.doFilterInternal(request, response, chain))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Simulated downstream database failure");

        verify(idempotencyService).releaseClaim(eq(userId), eq(idempotencyKey), any(), anyLong());
        verify(idempotencyService, never()).completeClaim(any(), any(), any(), anyLong(), any(), any(Integer.class), any(), any());
    }

    @Test
    @DisplayName("Reconciliation required claim result returns 409 Conflict without invoking downstream chain")
    void reconciliationRequired_returns409ConflictWithoutInvokingDownstream() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/migrations/submit");
        request.addHeader("Idempotency-Key", idempotencyKey);
        request.setContent("{\"reviewSessionId\":\"12345678-1234-1234-1234-123456789abc\"}".getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");

        MockHttpServletResponse response = new MockHttpServletResponse();

        when(userService.getUserByEmail(testUser.getEmail())).thenReturn(testUser);
        when(fingerprinter.resolveFromPayload(any(), any(), any()))
                .thenReturn(new CanonicalMigrationRequest(userId, "src", "target", List.of()));
        when(fingerprinter.calculateFingerprint(any(CanonicalMigrationRequest.class)))
                .thenReturn("v1:reconFingerprint");

        when(idempotencyService.acquireClaim(eq(userId), eq(idempotencyKey), eq("v1:reconFingerprint"), any()))
                .thenReturn(ClaimResult.reconciliationRequired(
                        "Migration execution requires reconciliation before retry to prevent duplicate destination playlist creation.",
                        null
                ));

        FilterChain chain = (req, res) -> {
            throw new AssertionError("Downstream filter chain must NOT be invoked when reconciliation is required!");
        };

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(response.getContentAsString()).contains("reconciliation");
        verify(idempotencyService, never()).completeClaim(any(), any(), any(), anyLong(), any(), any(Integer.class), any(), any());
    }

    @Test
    @DisplayName("Stale leader fenced out during completion is logged and does not fail client response")
    void staleLeaderFencedOut_catchesExceptionAndCompletes() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/migrations/submit");
        request.addHeader("Idempotency-Key", idempotencyKey);
        request.setContent("{\"reviewSessionId\":\"12345678-1234-1234-1234-123456789abc\"}".getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");

        MockHttpServletResponse response = new MockHttpServletResponse();

        when(userService.getUserByEmail(testUser.getEmail())).thenReturn(testUser);
        when(fingerprinter.resolveFromPayload(any(), any(), any()))
                .thenReturn(new CanonicalMigrationRequest(userId, "src", "target", List.of()));
        when(fingerprinter.calculateFingerprint(any(CanonicalMigrationRequest.class)))
                .thenReturn("v1:staleHash");

        MigrationIdempotencyRecord pendingRecord = MigrationIdempotencyRecord.createPending(
                userId, idempotencyKey, "v1:staleHash", null, null
        );
        when(idempotencyService.acquireClaim(eq(userId), eq(idempotencyKey), eq("v1:staleHash"), any()))
                .thenReturn(ClaimResult.acquired(pendingRecord, "stale-owner", 1L));

        UUID executionId = UUID.randomUUID();
        when(idempotencyService.completeClaim(any(), any(), any(), anyLong(), any(), any(Integer.class), any(), any()))
                .thenThrow(new StaleClaimOwnershipException(idempotencyKey, "stale-owner", 1L));

        FilterChain chain = (req, res) -> {
            res.setStatus(HttpStatus.ACCEPTED.value());
            res.setContentType("application/json");
            res.getWriter().write("{\"executionId\":\"" + executionId + "\",\"status\":\"QUEUED\"}");
        };

        // Filter must catch StaleClaimOwnershipException and not blow up with 500
        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.ACCEPTED.value());
    }
}
