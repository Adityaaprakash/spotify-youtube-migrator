package com.spotifyyoutube.migrator.migration.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.api.dto.CreateMigrationRequest;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationJobResponse;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationProgressResponse;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationSubmissionResponse;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationTaskResponse;
import com.spotifyyoutube.migrator.migration.api.dto.RunMigrationApiRequest;
import com.spotifyyoutube.migrator.migration.api.mapper.MigrationExecutionApiMapper;
import com.spotifyyoutube.migrator.migration.api.mapper.MigrationMapper;
import com.spotifyyoutube.migrator.migration.application.MigrationService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.async.MigrationJobDispatcher;
import com.spotifyyoutube.migrator.migration.progress.application.MigrationProgressService;
import com.spotifyyoutube.migrator.migration.progress.domain.MigrationProgressSnapshot;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * REST controller exposing asynchronous migration submission, progress tracking,
 * and legacy migration management endpoints (Phase 12E).
 * <p>
 * Enforces anti-IDOR security, authenticated user resolution, non-blocking asynchronous dispatch,
 * and read-only progress inspection.
 */
@RestController
@RequestMapping("/api/migrations")
public class MigrationController {

    private static final Logger log = LoggerFactory.getLogger(MigrationController.class);

    private final MigrationService migrationService;
    private final MigrationJobDispatcher jobDispatcher;
    private final MigrationProgressService progressService;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    @Autowired
    public MigrationController(
            MigrationService migrationService,
            @Autowired(required = false) MigrationJobDispatcher jobDispatcher,
            @Autowired(required = false) MigrationProgressService progressService,
            @Autowired(required = false) UserService userService,
            @Autowired(required = false) ObjectMapper objectMapper,
            @Autowired(required = false) Validator validator
    ) {
        this.migrationService = Objects.requireNonNull(migrationService, "MigrationService must not be null");
        this.jobDispatcher = jobDispatcher;
        this.progressService = progressService;
        this.userService = userService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.validator = validator;
    }

    public MigrationController(MigrationService migrationService) {
        this(migrationService, null, null, null, new ObjectMapper(), null);
    }

    /**
     * Submits an approved playlist migration for asynchronous execution (Phase 12E), or creates a legacy migration job.
     * <p>
     * If the payload contains {@code reviewSessionId}, coordinates synchronous preparation (Stage 1),
     * enqueues background worker execution (Stage 2), and returns HTTP 202 Accepted with a {@code Location} header.
     *
     * @param payload   the JSON request body
     * @param principal the authenticated principal
     * @return HTTP 202 Accepted with Location header for approved migrations, or HTTP 201 Created for legacy jobs
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> submitOrProcessMigration(
            @RequestBody JsonNode payload,
            Principal principal
    ) {
        if (payload == null || payload.isNull() || payload.isEmpty()) {
            throw new IllegalArgumentException("Request body must not be empty");
        }

        // Case 1: Asynchronous Approved Migration Submission (Phase 12E)
        if (payload.has("reviewSessionId") || payload.has("sessionId")) {
            return submitAsyncMigration(payload, principal);
        }

        // Case 2: Legacy Direct Migration Job Creation (Phase 1 / Phase 8)
        return createLegacyMigration(payload);
    }

    /**
     * Convenience typed endpoint submitting an approved migration asynchronously (Phase 12E).
     *
     * @param request   validated approved migration request
     * @param principal authenticated principal
     * @return HTTP 202 Accepted with Location header pointing to progress URL
     */
    @PostMapping(value = "/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MigrationSubmissionResponse> submitMigrationDirect(
            @RequestBody @Valid RunMigrationApiRequest request,
            Principal principal
    ) {
        if (jobDispatcher == null) {
            throw new IllegalStateException("MigrationJobDispatcher is not configured");
        }
        UUID userId = resolveUserId(request.userId(), principal);
        ApprovedMigrationExecutionRequest domainReq = MigrationExecutionApiMapper.toDomainRequest(request, userId);

        MigrationExecutionSummary summary = jobDispatcher.dispatchMigration(domainReq, userId);
        MigrationSubmissionResponse response = MigrationSubmissionResponse.from(summary);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create(response.progressUrl()))
                .body(response);
    }

    /**
     * Retrieves real-time execution progress and per-track distribution by execution ID (Phase 12E-C).
     * Safe for high-frequency polling; strictly read-only.
     *
     * @param executionId the execution identifier
     * @param principal   the authenticated principal
     * @return HTTP 200 OK with migration progress snapshot response
     */
    @GetMapping("/{executionId}/progress")
    public ResponseEntity<MigrationProgressResponse> getExecutionProgress(
            @PathVariable UUID executionId,
            Principal principal
    ) {
        if (progressService == null) {
            throw new IllegalStateException("MigrationProgressService is not configured");
        }
        UUID userId = resolveUserId(null, principal);
        MigrationProgressSnapshot snapshot = progressService.getProgress(executionId, userId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(MigrationProgressResponse.from(snapshot));
    }

    /**
     * Retrieves real-time execution progress by associated review-session ID (Phase 12E-D).
     *
     * @param reviewSessionId the review session identifier
     * @param principal       the authenticated principal
     * @return HTTP 200 OK with migration progress snapshot response
     */
    @GetMapping("/review-sessions/{reviewSessionId}/progress")
    public ResponseEntity<MigrationProgressResponse> getReviewSessionProgress(
            @PathVariable UUID reviewSessionId,
            Principal principal
    ) {
        if (progressService == null) {
            throw new IllegalStateException("MigrationProgressService is not configured");
        }
        UUID userId = resolveUserId(null, principal);
        MigrationProgressSnapshot snapshot = progressService.getProgressByReviewSession(reviewSessionId, userId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(MigrationProgressResponse.from(snapshot));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MigrationJobResponse> getMigration(@PathVariable UUID id) {
        var job = migrationService.getMigrationJobById(id);
        return ResponseEntity.ok(MigrationMapper.toJobResponse(job));
    }

    @GetMapping("/{id}/tasks")
    public ResponseEntity<List<MigrationTaskResponse>> getMigrationTasks(@PathVariable UUID id) {
        var tasks = migrationService.getMigrationTasks(id);
        return ResponseEntity.ok(MigrationMapper.toTaskResponseList(tasks));
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<MigrationJobResponse> executeMigration(@PathVariable UUID id) {
        var job = migrationService.executeMigration(id);
        return ResponseEntity.ok(MigrationMapper.toJobResponse(job));
    }

    private ResponseEntity<MigrationSubmissionResponse> submitAsyncMigration(JsonNode payload, Principal principal) {
        if (jobDispatcher == null) {
            throw new IllegalStateException("MigrationJobDispatcher is not configured");
        }

        RunMigrationApiRequest request;
        try {
            request = objectMapper.treeToValue(payload, RunMigrationApiRequest.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed approved migration request: " + e.getMessage(), e);
        }

        if (validator != null) {
            Set<ConstraintViolation<RunMigrationApiRequest>> violations = validator.validate(request);
            if (!violations.isEmpty()) {
                throw new IllegalArgumentException(violations.iterator().next().getMessage());
            }
        }

        UUID userId = resolveUserId(request.userId(), principal);
        ApprovedMigrationExecutionRequest domainReq = MigrationExecutionApiMapper.toDomainRequest(request, userId);

        MigrationExecutionSummary summary = jobDispatcher.dispatchMigration(domainReq, userId);
        MigrationSubmissionResponse response = MigrationSubmissionResponse.from(summary);

        log.info("Accepted approved migration submission for review session {} -> executionId={}",
                request.reviewSessionId(), response.executionId());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create(response.progressUrl()))
                .body(response);
    }

    private ResponseEntity<MigrationJobResponse> createLegacyMigration(JsonNode payload) {
        CreateMigrationRequest request;
        try {
            request = objectMapper.treeToValue(payload, CreateMigrationRequest.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed migration request: " + e.getMessage(), e);
        }

        if (validator != null) {
            Set<ConstraintViolation<CreateMigrationRequest>> violations = validator.validate(request);
            if (!violations.isEmpty()) {
                throw new IllegalArgumentException(violations.iterator().next().getMessage());
            }
        } else {
            if (request.userId() == null || request.sourcePlaylistId() == null || request.sourcePlaylistId().isBlank()
                    || request.sourcePlatform() == null || request.targetPlatform() == null) {
                throw new IllegalArgumentException("Validation failed for CreateMigrationRequest");
            }
        }

        var job = migrationService.createMigrationJob(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(MigrationMapper.toJobResponse(job));
    }

    private UUID resolveUserId(UUID requestedUserId, Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new IllegalArgumentException("User must be authenticated");
        }
        if (userService == null) {
            throw new IllegalStateException("UserService is not configured");
        }

        String identifier = principal.getName();
        User user;
        try {
            user = userService.getUserByEmail(identifier);
        } catch (ResourceNotFoundException e) {
            try {
                user = userService.getUserById(UUID.fromString(identifier));
            } catch (Exception ex) {
                throw new ResourceNotFoundException("User not found for authenticated principal: " + identifier);
            }
        }

        if (user == null || user.getId() == null) {
            throw new ResourceNotFoundException("User not found for authenticated principal: " + identifier);
        }

        // Security check: caller-supplied userId must not impersonate another user
        if (requestedUserId != null && !user.getId().equals(requestedUserId)) {
            throw new IllegalArgumentException("Authenticated user does not match the requested user ID");
        }

        return user.getId();
    }
}
