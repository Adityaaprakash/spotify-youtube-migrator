package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.exception.ApiError;
import com.spotifyyoutube.migrator.common.exception.ErrorCode;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Servlet filter providing transparent, distributed HTTP API idempotency for migration submission (Phase 13E).
 * <p>
 * Implements a database-backed atomic claim protocol:
 * <ul>
 *   <li>Intercepts legacy {@code POST /api/migrations} (key optional) and typed
 *       {@code POST /api/migrations/submit} (key mandatory)</li>
 *   <li>Enforces key syntax (max 255 chars, non-blank, printable ASCII)</li>
 *   <li>Scopes key ownership to the authenticated user {@code (user_id, idempotency_key)}</li>
 *   <li>Computes canonical request fingerprint {@code v1:SHA-256(...)} using Unicode NFC normalization</li>
 *   <li>Atomically acquires leader claim reservation before execution creation or background dispatch</li>
 *   <li>Only the winning leader thread executes downstream; concurrent matching requests wait/poll safely</li>
 *   <li>Rejects conflicting payload reuse on the same key with HTTP 409 Conflict</li>
 *   <li>Preserves the request stream for downstream Spring MVC controllers via {@link CachedBodyHttpServletRequest}</li>
 * </ul>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class ApiIdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiIdempotencyFilter.class);

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String SUBMIT_ENDPOINT = "/api/migrations/submit";
    private static final String LEGACY_ENDPOINT = "/api/migrations";

    private final MigrationIdempotencyService idempotencyService;
    private final CanonicalRequestFingerprinter fingerprinter;
    private final ReviewSessionRepository reviewSessionRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final IdempotencyClaimHeartbeatService claimHeartbeatService;

    public ApiIdempotencyFilter(
            MigrationIdempotencyService idempotencyService,
            CanonicalRequestFingerprinter fingerprinter,
            ReviewSessionRepository reviewSessionRepository,
            UserService userService,
            ObjectMapper objectMapper
    ) {
        this(idempotencyService, fingerprinter, reviewSessionRepository, userService, objectMapper, null);
    }

    @Autowired
    public ApiIdempotencyFilter(
            MigrationIdempotencyService idempotencyService,
            CanonicalRequestFingerprinter fingerprinter,
            ReviewSessionRepository reviewSessionRepository,
            UserService userService,
            @Autowired(required = false) ObjectMapper objectMapper,
            @Autowired(required = false) IdempotencyClaimHeartbeatService claimHeartbeatService
    ) {
        this.idempotencyService = Objects.requireNonNull(idempotencyService, "idempotencyService must not be null");
        this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter must not be null");
        this.reviewSessionRepository = reviewSessionRepository;
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.claimHeartbeatService = claimHeartbeatService;
    }


    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return !isSubmissionEndpoint(request.getRequestURI());
    }

    private boolean isSubmissionEndpoint(String uri) {
        if (uri == null) {
            return false;
        }
        String normalized = uri.endsWith("/") && uri.length() > 1
                ? uri.substring(0, uri.length() - 1)
                : uri;
        return normalized.equals(LEGACY_ENDPOINT) || normalized.equals(SUBMIT_ENDPOINT);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String rawKey = request.getHeader(IDEMPOTENCY_KEY_HEADER);
        boolean isTypedSubmit = isTypedSubmitEndpoint(request.getRequestURI());

        // 1. Mandatory Header Enforcement on Typed Endpoint
        if (isTypedSubmit && rawKey == null) {
            writeErrorResponse(
                    response,
                    request.getRequestURI(),
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.INVALID_REQUEST,
                    "Idempotency-Key header is required for " + SUBMIT_ENDPOINT
            );
            return;
        }

        // 2. Key Validation if Present
        if (rawKey != null) {
            var validation = IdempotencyKeyValidator.validate(rawKey);
            if (!validation.isValid()) {
                writeErrorResponse(
                        response,
                        request.getRequestURI(),
                        HttpStatus.BAD_REQUEST,
                        ErrorCode.INVALID_REQUEST,
                        validation.errorMessage()
                );
                return;
            }
        } else {
            // Optional key missing on legacy endpoint -> proceed normally
            filterChain.doFilter(request, response);
            return;
        }

        String idempotencyKey = rawKey.trim();

        // 3. User Identity Resolution from Security Context
        UUID userId = resolveAuthenticatedUserId();
        if (userId == null) {
            // Unauthenticated request; delegate to Spring Security filter
            filterChain.doFilter(request, response);
            return;
        }

        // 4. Wrap Request to Preserve Stream
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request);

        // 5. Compute Canonical Fingerprint from Cached Body
        String fingerprint;
        UUID reviewSessionId = null;
        try {
            JsonNode payloadNode = objectMapper.readTree(wrappedRequest.getCachedBody());
            if (payloadNode == null || payloadNode.isNull() || payloadNode.isEmpty()) {
                // Empty payload; let downstream validation handle it
                filterChain.doFilter(wrappedRequest, response);
                return;
            }
            reviewSessionId = extractReviewSessionId(payloadNode);
            CanonicalMigrationRequest canonicalReq = fingerprinter.resolveFromPayload(
                    userId, payloadNode, reviewSessionRepository
            );
            fingerprint = fingerprinter.calculateFingerprint(canonicalReq);
        } catch (Exception ex) {
            // Malformed JSON; pass to downstream controller/validator
            filterChain.doFilter(wrappedRequest, response);
            return;
        }

        // 6. Database-Backed Atomic Claim Reservation (BEFORE execution creation / dispatch)
        ClaimResult claimResult = idempotencyService.acquireClaim(
                userId, idempotencyKey, fingerprint, reviewSessionId
        );

        if (claimResult.isMismatch()) {
            writeErrorResponse(
                    response,
                    request.getRequestURI(),
                    HttpStatus.CONFLICT,
                    ErrorCode.IDEMPOTENCY_FINGERPRINT_MISMATCH,
                    claimResult.message() != null ? claimResult.message() :
                            String.format("Idempotency key '%s' was previously used with a different request payload",
                                    maskKey(idempotencyKey))
            );
            return;
        }

        if (claimResult.isReconciliationRequired()) {
            log.warn("Migration submission for key {} rejected: reconciliation required due to unconfirmed external operation before crash",
                    maskKey(idempotencyKey));
            writeErrorResponse(
                    response,
                    request.getRequestURI(),
                    HttpStatus.CONFLICT,
                    ErrorCode.CONFLICT,
                    claimResult.message() != null ? claimResult.message() :
                            "Migration execution requires reconciliation before retry to prevent duplicate destination playlist creation."
            );
            return;
        }

        if (claimResult.isReplay()) {
            MigrationIdempotencyRecord completedRecord = claimResult.getRecord()
                    .orElseThrow(() -> new IllegalStateException("Replay record missing"));
            writeReplayResponse(response, completedRecord);
            return;
        }

        if (claimResult.isPending()) {
            // Concurrent follower request: wait/poll safely for winning leader to complete
            boolean replayed = pollAndReplay(
                    userId, idempotencyKey, fingerprint, request.getRequestURI(), response
            );
            if (!replayed) {
                writeErrorResponse(
                    response,
                    request.getRequestURI(),
                    HttpStatus.CONFLICT,
                    ErrorCode.IDEMPOTENCY_FINGERPRINT_MISMATCH,
                    "Concurrent migration request is still in progress. Please retry shortly."
                );
            }
            return;
        }

        // 7. Winning Leader Execution: Proceed Downstream with Fencing Tokens
        String claimOwner = claimResult.claimOwner();
        long claimGeneration = claimResult.claimGeneration();
        UUID claimRecordId = claimResult.getRecord().map(MigrationIdempotencyRecord::getId).orElse(null);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        boolean completed = false;

        if (claimRecordId != null) {
            ClaimOwnershipContext.set(new ClaimOwnershipContext(
                    claimRecordId,
                    userId,
                    idempotencyKey,
                    claimOwner,
                    claimGeneration
            ));
            if (claimHeartbeatService != null) {
                claimHeartbeatService.registerClaim(
                        claimRecordId,
                        userId,
                        idempotencyKey,
                        claimOwner,
                        claimGeneration,
                        Duration.ofSeconds(60)
                );
            }
        }

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);

            int responseStatus = wrappedResponse.getStatus();
            byte[] responseBytes = wrappedResponse.getContentAsByteArray();
            String responsePayload = new String(responseBytes, StandardCharsets.UTF_8);

            UUID executionId = extractExecutionId(responsePayload);
            String locationHeader = wrappedResponse.getHeader(HttpHeaders.LOCATION);
            if (locationHeader == null && executionId != null) {
                locationHeader = "/api/migrations/" + executionId + "/progress";
            }

            if (responseStatus >= 200 && responseStatus < 300 && executionId != null) {
                if (claimHeartbeatService != null && claimRecordId != null && claimHeartbeatService.isOwnershipLost(claimRecordId)) {
                    log.warn("Leader lost claim ownership during preparation for key {}. Fencing out completion.", idempotencyKey);
                } else {
                    try {
                        idempotencyService.completeClaim(
                                userId,
                                idempotencyKey,
                                claimOwner,
                                claimGeneration,
                                executionId,
                                responseStatus,
                                responsePayload,
                                locationHeader
                        );
                        completed = true;
                    } catch (StaleClaimOwnershipException ex) {
                        log.warn("Stale leader fenced out during completion for key {}: attempted owner {}, gen {}",
                                idempotencyKey, claimOwner, claimGeneration);
                    }
                }
            } else {
                // Non-2xx response or execution not created: release pending claim so client can retry
                if (executionId == null) {
                    try {
                        idempotencyService.releaseClaim(userId, idempotencyKey, claimOwner, claimGeneration);
                    } catch (StaleClaimOwnershipException ex) {
                        log.warn("Stale leader unable to release claim for key {}: {}", idempotencyKey, ex.getMessage());
                    }
                }
            }

            wrappedResponse.copyBodyToResponse();
        } catch (Throwable ex) {
            if (!completed) {
                try {
                    idempotencyService.releaseClaim(userId, idempotencyKey, claimOwner, claimGeneration);
                } catch (StaleClaimOwnershipException staleEx) {
                    log.warn("Stale leader failed to release claim on error: {}", staleEx.getMessage());
                }
            }
            throw ex;
        } finally {
            if (claimHeartbeatService != null && claimRecordId != null) {
                claimHeartbeatService.unregisterClaim(claimRecordId);
            }
            ClaimOwnershipContext.clear();
        }
    }


    private boolean pollAndReplay(
            UUID userId,
            String idempotencyKey,
            String fingerprint,
            String requestUri,
            HttpServletResponse response
    ) throws IOException {
        int maxAttempts = 100; // 100 * 50ms = 5 seconds
        for (int i = 0; i < maxAttempts; i++) {
            Optional<MigrationIdempotencyRecord> opt =
                    idempotencyService.findExistingRecord(userId, idempotencyKey);
            if (opt.isPresent()) {
                MigrationIdempotencyRecord record = opt.get();
                if (!record.getRequestFingerprint().equals(fingerprint)) {
                    writeErrorResponse(
                            response,
                            requestUri,
                            HttpStatus.CONFLICT,
                            ErrorCode.IDEMPOTENCY_FINGERPRINT_MISMATCH,
                            String.format("Idempotency key '%s' was previously used with a different request payload",
                                    maskKey(idempotencyKey))
                    );
                    return true;
                }
                if (record.getStatus() == IdempotencyClaimStatus.COMPLETED) {
                    writeReplayResponse(response, record);
                    return true;
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return false;
    }

    private UUID extractReviewSessionId(JsonNode payload) {
        if (payload == null) {
            return null;
        }
        String sessionStr = null;
        if (payload.hasNonNull("reviewSessionId")) {
            sessionStr = payload.get("reviewSessionId").asText();
        } else if (payload.hasNonNull("sessionId")) {
            sessionStr = payload.get("sessionId").asText();
        }
        if (sessionStr != null && !sessionStr.isBlank()) {
            try {
                return UUID.fromString(sessionStr);
            } catch (Exception ignored) {
                // Ignore unparseable UUID
            }
        }
        return null;
    }

    private boolean isTypedSubmitEndpoint(String uri) {
        if (uri == null) {
            return false;
        }
        String normalized = uri.endsWith("/") && uri.length() > 1
                ? uri.substring(0, uri.length() - 1)
                : uri;
        return normalized.equals(SUBMIT_ENDPOINT);
    }

    private UUID resolveAuthenticatedUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equalsIgnoreCase(auth.getName())) {
            return null;
        }

        String identifier = auth.getName();
        try {
            User user = userService.getUserByEmail(identifier);
            return user.getId();
        } catch (Exception e) {
            try {
                return UUID.fromString(identifier);
            } catch (Exception ex) {
                log.warn("Unable to resolve UUID for authenticated principal: {}", identifier);
                return null;
            }
        }
    }

    private UUID extractExecutionId(String responsePayload) {
        if (responsePayload == null || responsePayload.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(responsePayload);
            if (root.hasNonNull("executionId")) {
                return UUID.fromString(root.get("executionId").asText());
            }
            if (root.hasNonNull("id")) {
                return UUID.fromString(root.get("id").asText());
            }
        } catch (Exception ignored) {
            // Unparseable response body
        }
        return null;
    }

    private void writeReplayResponse(
            HttpServletResponse response,
            MigrationIdempotencyRecord record
    ) throws IOException {
        response.setStatus(record.getResponseStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        if (record.getLocationHeader() != null && !record.getLocationHeader().isBlank()) {
            response.setHeader(HttpHeaders.LOCATION, record.getLocationHeader());
        } else {
            try {
                JsonNode root = objectMapper.readTree(record.getResponsePayload());
                if (root.hasNonNull("progressUrl")) {
                    response.setHeader(HttpHeaders.LOCATION, root.get("progressUrl").asText());
                }
            } catch (Exception ignored) {
                // Location extraction optional
            }
        }

        response.getWriter().write(record.getResponsePayload());
        response.getWriter().flush();
    }

    private void writeErrorResponse(
            HttpServletResponse response,
            String path,
            HttpStatus status,
            ErrorCode errorCode,
            String message
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ApiError error = new ApiError(
                errorCode,
                message,
                Instant.now().toString(),
                path
        );

        response.getWriter().write(objectMapper.writeValueAsString(error));
        response.getWriter().flush();
    }

    private String maskKey(String key) {
        if (key == null || key.length() <= 8) {
            return "***";
        }
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}
