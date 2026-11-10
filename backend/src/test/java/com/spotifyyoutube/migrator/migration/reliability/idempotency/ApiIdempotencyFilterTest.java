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
import static org.mockito.ArgumentMatchers.any;
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

        // Filter chain invoked without error
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
    @DisplayName("First request with valid key computes fingerprint, executes downstream, and records idempotency")
    void validFirstRequest_executesDownstreamAndRecordsIdempotency() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/migrations/submit");
        request.addHeader("Idempotency-Key", idempotencyKey);
        String requestJson = "{\"reviewSessionId\":\"" + UUID.randomUUID() + "\"}";
        request.setContent(requestJson.getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");

        MockHttpServletResponse response = new MockHttpServletResponse();

        when(userService.getUserByEmail(testUser.getEmail())).thenReturn(testUser);
        when(fingerprinter.resolveFromPayload(any(), any(), any()))
                .thenReturn(new CanonicalMigrationRequest(userId, "src", "target", List.of()));
        when(fingerprinter.calculateFingerprint(any(CanonicalMigrationRequest.class)))
                .thenReturn("v1:calculatedhash123");
        when(idempotencyService.findExistingRecord(userId, idempotencyKey))
                .thenReturn(Optional.empty());

        UUID executionId = UUID.randomUUID();
        FilterChain chain = (req, res) -> {
            // Verify request body can be read downstream without stream closed error
            byte[] bytes = req.getInputStream().readAllBytes();
            assertThat(bytes).isNotEmpty();

            res.setStatus(HttpStatus.ACCEPTED.value());
            res.setContentType("application/json");
            res.getWriter().write("{\"executionId\":\"" + executionId + "\",\"status\":\"QUEUED\"}");
        };

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.ACCEPTED.value());
        verify(idempotencyService).recordSubmission(
                eq(userId), eq(idempotencyKey), eq("v1:calculatedhash123"),
                eq(executionId), eq(202), any()
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
                userId, idempotencyKey, expectedFingerprint, originalExecId, 202, originalPayload, null, null
        );

        when(idempotencyService.findExistingRecord(userId, idempotencyKey))
                .thenReturn(Optional.of(existing));

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

        MigrationIdempotencyRecord existing = new MigrationIdempotencyRecord(
                userId, idempotencyKey, "v1:oldFingerprintInitial", UUID.randomUUID(), 202, "{}", null, null
        );

        when(idempotencyService.findExistingRecord(userId, idempotencyKey))
                .thenReturn(Optional.of(existing));

        FilterChain chain = (req, res) -> {
            throw new AssertionError("Downstream filter chain must NOT be invoked on fingerprint conflict!");
        };

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(response.getContentAsString()).contains(ErrorCode.IDEMPOTENCY_FINGERPRINT_MISMATCH.name());
        assertThat(response.getContentAsString()).contains("previously used with a different request payload");
    }

    @Test
    @DisplayName("Concurrent race: downstream 409 conflict resolves winning record and replays on matching fingerprint")
    void concurrentRace_downstreamConflict_resolvesWinningRecordAndReplays() throws ServletException, IOException {
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
        MigrationIdempotencyRecord winningRecord = new MigrationIdempotencyRecord(
                userId, idempotencyKey, expectedFingerprint, winningExecId, 202, winningPayload, null, null
        );

        // First find is empty, simulating concurrent race where record wasn't committed yet
        when(idempotencyService.findExistingRecord(userId, idempotencyKey))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winningRecord));

        FilterChain chain = (req, res) -> {
            // Downstream reports conflict because concurrent thread is processing
            res.setStatus(HttpStatus.CONFLICT.value());
            res.getWriter().write("{\"code\":\"INVALID_STATE\",\"message\":\"Migration already queued\"}");
        };

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.ACCEPTED.value());
        assertThat(response.getContentAsString()).isEqualTo(winningPayload);
    }

    @Test
    @DisplayName("Concurrent race: downstream 409 conflict with mismatched winner returns IDEMPOTENCY_FINGERPRINT_MISMATCH")
    void concurrentRace_downstreamConflict_mismatchedWinner_returnsFingerprintMismatch()
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/migrations/submit");
        request.addHeader("Idempotency-Key", idempotencyKey);
        request.setContent("{\"reviewSessionId\":\"12345678-1234-1234-1234-123456789abc\"}".getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");

        MockHttpServletResponse response = new MockHttpServletResponse();

        when(userService.getUserByEmail(testUser.getEmail())).thenReturn(testUser);
        when(fingerprinter.resolveFromPayload(any(), any(), any()))
                .thenReturn(new CanonicalMigrationRequest(userId, "src", "target", List.of()));
        when(fingerprinter.calculateFingerprint(any(CanonicalMigrationRequest.class)))
                .thenReturn("v1:myDifferentFingerprint");

        MigrationIdempotencyRecord winningRecord = new MigrationIdempotencyRecord(
                userId, idempotencyKey, "v1:otherFingerprintFromWinner", UUID.randomUUID(), 202, "{}", null, null
        );

        when(idempotencyService.findExistingRecord(userId, idempotencyKey))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winningRecord));

        FilterChain chain = (req, res) -> {
            res.setStatus(HttpStatus.CONFLICT.value());
            res.getWriter().write("{\"code\":\"INVALID_STATE\"}");
        };

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(response.getContentAsString()).contains(ErrorCode.IDEMPOTENCY_FINGERPRINT_MISMATCH.name());
    }
}
