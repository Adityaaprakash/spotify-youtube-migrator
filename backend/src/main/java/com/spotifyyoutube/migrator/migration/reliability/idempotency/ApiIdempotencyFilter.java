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
 * Responsibilities:
 * <ul>
 *   <li>Intercepts legacy {@code POST /api/migrations} (key optional) and typed
 *       {@code POST /api/migrations/submit} (key mandatory)</li>
 *   <li>Enforces key syntax (max 255 chars, non-blank, printable ASCII)</li>
 *   <li>Scopes key ownership to the authenticated user {@code (user_id, idempotency_key)}</li>
 *   <li>Computes canonical request fingerprint {@code v1:SHA-256(...)} using unambiguous delimiters</li>
 *   <li>Detects and replays matching submissions without duplicate aggregate creation or worker dispatch</li>
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

    @Autowired
    public ApiIdempotencyFilter(
            MigrationIdempotencyService idempotencyService,
            CanonicalRequestFingerprinter fingerprinter,
            ReviewSessionRepository reviewSessionRepository,
            UserService userService,
            @Autowired(required = false) ObjectMapper objectMapper
    ) {
        this.idempotencyService = Objects.requireNonNull(idempotencyService, "idempotencyService must not be null");
        this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter must not be null");
        this.reviewSessionRepository = reviewSessionRepository;
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
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
        try {
            JsonNode payloadNode = objectMapper.readTree(wrappedRequest.getCachedBody());
            if (payloadNode == null || payloadNode.isNull() || payloadNode.isEmpty()) {
                // Empty payload; let downstream validation handle it
                filterChain.doFilter(wrappedRequest, response);
                return;
            }
            CanonicalMigrationRequest canonicalReq = fingerprinter.resolveFromPayload(
                    userId, payloadNode, reviewSessionRepository
            );
            fingerprint = fingerprinter.calculateFingerprint(canonicalReq);
        } catch (Exception ex) {
            // If body is malformed JSON, pass to downstream controller/validator
            filterChain.doFilter(wrappedRequest, response);
            return;
        }

        // 6. Fast-Path: Replay Matching Record if Already Persisted
        Optional<MigrationIdempotencyRecord> existingOpt =
                idempotencyService.findExistingRecord(userId, idempotencyKey);

        if (existingOpt.isPresent()) {
            MigrationIdempotencyRecord existing = existingOpt.get();
            if (existing.getRequestFingerprint().equals(fingerprint)) {
                writeReplayResponse(response, existing);
                return;
            } else {
                writeErrorResponse(
                        response,
                        request.getRequestURI(),
                        HttpStatus.CONFLICT,
                        ErrorCode.IDEMPOTENCY_FINGERPRINT_MISMATCH,
                        String.format("Idempotency key '%s' was previously used with a different request payload",
                                maskKey(idempotencyKey))
                );
                return;
            }
        }

        // 7. Initial Execution Path with Response Caching Wrapper
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } catch (Exception ex) {
            // Concurrency race: check if another concurrent thread with identical key committed
            Optional<MigrationIdempotencyRecord> winner = pollForWinner(userId, idempotencyKey, 10, 100);
            if (winner.isPresent()) {
                MigrationIdempotencyRecord winningRecord = winner.get();
                if (winningRecord.getRequestFingerprint().equals(fingerprint)) {
                    writeReplayResponse(response, winningRecord);
                    return;
                } else {
                    writeErrorResponse(
                            response,
                            request.getRequestURI(),
                            HttpStatus.CONFLICT,
                            ErrorCode.IDEMPOTENCY_FINGERPRINT_MISMATCH,
                            String.format("Idempotency key '%s' was previously used with a different request payload",
                                    maskKey(idempotencyKey))
                    );
                    return;
                }
            }
            throw ex;
        }

        // 8. Capture Downstream Successful Response and Persist Idempotency Record
        int responseStatus = wrappedResponse.getStatus();

        // Concurrency race resolution: if downstream returned 409 Conflict, check if another thread won and committed
        if (responseStatus == HttpStatus.CONFLICT.value()) {
            Optional<MigrationIdempotencyRecord> winner = pollForWinner(userId, idempotencyKey, 10, 50);
            if (winner.isPresent()) {
                MigrationIdempotencyRecord winningRecord = winner.get();
                if (winningRecord.getRequestFingerprint().equals(fingerprint)) {
                    writeReplayResponse(response, winningRecord);
                    return;
                } else {
                    writeErrorResponse(
                            response,
                            request.getRequestURI(),
                            HttpStatus.CONFLICT,
                            ErrorCode.IDEMPOTENCY_FINGERPRINT_MISMATCH,
                            String.format("Idempotency key '%s' was previously used with a different request payload",
                                    maskKey(idempotencyKey))
                    );
                    return;
                }
            }
        }

        if (responseStatus == HttpStatus.OK.value()
                || responseStatus == HttpStatus.ACCEPTED.value()
                || responseStatus == HttpStatus.CREATED.value()) {

            byte[] responseBytes = wrappedResponse.getContentAsByteArray();
            String responsePayload = new String(responseBytes, StandardCharsets.UTF_8);

            UUID executionId = extractExecutionId(responsePayload);
            if (executionId != null) {
                try {
                    MigrationIdempotencyRecord savedRecord = idempotencyService.recordSubmission(
                            userId,
                            idempotencyKey,
                            fingerprint,
                            executionId,
                            responseStatus,
                            responsePayload
                    );
                    if (!savedRecord.getExecutionId().equals(executionId)) {
                        writeReplayResponse(response, savedRecord);
                        return;
                    }
                } catch (IdempotencyFingerprintMismatchException ex) {
                    writeErrorResponse(
                            response,
                            request.getRequestURI(),
                            HttpStatus.CONFLICT,
                            ErrorCode.IDEMPOTENCY_FINGERPRINT_MISMATCH,
                            ex.getMessage()
                    );
                    return;
                }
            }
        }

        wrappedResponse.copyBodyToResponse();
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
            // Unparseable response body; cannot extract executionId
        }
        return null;
    }

    private Optional<MigrationIdempotencyRecord> pollForWinner(
            UUID userId,
            String idempotencyKey,
            int maxAttempts,
            long delayMs
    ) {
        for (int i = 0; i < maxAttempts; i++) {
            Optional<MigrationIdempotencyRecord> opt =
                    idempotencyService.findExistingRecord(userId, idempotencyKey);
            if (opt.isPresent()) {
                return opt;
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return Optional.empty();
    }

    private void writeReplayResponse(
            HttpServletResponse response,
            MigrationIdempotencyRecord record
    ) throws IOException {
        response.setStatus(record.getResponseStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        // Extract progressUrl for Location header if present in payload
        try {
            JsonNode root = objectMapper.readTree(record.getResponsePayload());
            if (root.hasNonNull("progressUrl")) {
                response.setHeader(HttpHeaders.LOCATION, root.get("progressUrl").asText());
            }
        } catch (Exception ignored) {
            // Location extraction optional
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
