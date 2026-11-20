package com.spotifyyoutube.migrator.migration.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.api.dto.CreateMigrationRequest;
import com.spotifyyoutube.migrator.migration.api.dto.ExecutionTrackRecordResponse;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationAuditEventResponse;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationExecutionResponse;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationHistoryItemResponse;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationJobResponse;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationProgressResponse;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationSubmissionResponse;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationTaskResponse;
import com.spotifyyoutube.migrator.migration.api.dto.PagedResponse;
import com.spotifyyoutube.migrator.migration.api.dto.RunMigrationApiRequest;
import com.spotifyyoutube.migrator.migration.api.mapper.MigrationExecutionApiMapper;
import com.spotifyyoutube.migrator.migration.api.mapper.MigrationMapper;
import com.spotifyyoutube.migrator.migration.application.MigrationService;
import com.spotifyyoutube.migrator.migration.application.execution.ExecutionAuditService;
import com.spotifyyoutube.migrator.migration.application.execution.MigrationExecutionStateService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionTrackRecordDto;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.async.MigrationJobDispatcher;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionAuditEvent;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.progress.application.MigrationProgressService;
import com.spotifyyoutube.migrator.migration.progress.application.ProgressPollingRateLimiter;
import com.spotifyyoutube.migrator.migration.progress.domain.MigrationProgressSnapshot;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * REST controller exposing asynchronous migration submission, progress tracking,
 * and legacy migration management endpoints (Phase 12E & 12F).
 * <p>
 * Enforces anti-IDOR security, authenticated user resolution, non-blocking asynchronous dispatch,
 * read-only progress inspection, and progress polling rate limiting.
 */
@RestController
@RequestMapping("/api/migrations")
public class MigrationController {

    private static final Logger log = LoggerFactory.getLogger(MigrationController.class);

    private final MigrationService migrationService;
    private final MigrationJobDispatcher jobDispatcher;
    private final MigrationProgressService progressService;
    private final ProgressPollingRateLimiter rateLimiter;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final MigrationExecutionRepository executionRepository;
    private final ExecutionAuditService auditService;
    private final MigrationExecutionStateService executionStateService;
    private final ExecutionTrackRecordRepository trackRecordRepository;

    @Autowired
    public MigrationController(
            MigrationService migrationService,
            MigrationJobDispatcher jobDispatcher,
            MigrationProgressService progressService,
            ProgressPollingRateLimiter rateLimiter,
            UserService userService,
            ObjectMapper objectMapper,
            Validator validator,
            @Autowired(required = false) MigrationExecutionRepository executionRepository,
            @Autowired(required = false) ExecutionAuditService auditService,
            @Autowired(required = false) MigrationExecutionStateService executionStateService,
            @Autowired(required = false) ExecutionTrackRecordRepository trackRecordRepository
    ) {
        this.migrationService = Objects.requireNonNull(migrationService, "MigrationService must not be null");
        this.jobDispatcher = Objects.requireNonNull(jobDispatcher, "MigrationJobDispatcher must not be null");
        this.progressService = Objects.requireNonNull(progressService, "MigrationProgressService must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "ProgressPollingRateLimiter must not be null");
        this.userService = Objects.requireNonNull(userService, "UserService must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.validator = validator;
        this.executionRepository = executionRepository;
        this.auditService = auditService;
        this.executionStateService = executionStateService;
        this.trackRecordRepository = trackRecordRepository;
    }

    public MigrationController(
            MigrationService migrationService,
            MigrationJobDispatcher jobDispatcher,
            MigrationProgressService progressService,
            ProgressPollingRateLimiter rateLimiter,
            UserService userService,
            ObjectMapper objectMapper,
            Validator validator,
            MigrationExecutionRepository executionRepository,
            ExecutionAuditService auditService
    ) {
        this(migrationService, jobDispatcher, progressService, rateLimiter, userService, objectMapper,
                validator, executionRepository, auditService, null, null);
    }

    public MigrationController(
            MigrationService migrationService,
            MigrationJobDispatcher jobDispatcher,
            MigrationProgressService progressService,
            ProgressPollingRateLimiter rateLimiter,
            UserService userService,
            ObjectMapper objectMapper,
            Validator validator
    ) {
        this(migrationService, jobDispatcher, progressService, rateLimiter, userService, objectMapper,
                validator, null, null, null, null);
    }

    /**
     * Submits an approved playlist migration for asynchronous execution (Phase 12E), or creates a legacy migration job.
     * <p>
     * If the payload contains {@code reviewSessionId}, coordinates synchronous preparation (Stage 1),
     * enqueues background worker execution (Stage 2), and returns HTTP 202 Accepted with a {@code Location} header
     * (or HTTP 200 OK if already COMPLETED).
     *
     * @param payload   the JSON request body
     * @param principal the authenticated principal
     * @return HTTP 202 Accepted (or 200 OK) with Location header for approved migrations, or HTTP 201 Created for legacy jobs
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
        if (payload.has("reviewSessionId") || payload.has("sessionId")
                || payload.has("targetPlaylistTitle") || payload.has("targetPlaylistDescription")) {
            return submitAsyncMigration(payload, principal);
        }

        // Case 2: Legacy Direct Migration Job Creation (Phase 1 / Phase 8)
        if (payload.has("sourcePlatform") || payload.has("targetPlatform")) {
            return createLegacyMigration(payload);
        }

        throw new IllegalArgumentException(
                "Unrecognized migration request: must specify reviewSessionId for approved migration "
                        + "or sourcePlatform/targetPlatform for legacy migration");
    }

    /**
     * Convenience typed endpoint submitting an approved migration asynchronously (Phase 12E).
     *
     * @param request   validated approved migration request
     * @param principal authenticated principal
     * @return HTTP 202 Accepted (or 200 OK) with Location header pointing to progress URL
     */
    @PostMapping(value = "/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MigrationSubmissionResponse> submitMigrationDirect(
            @RequestBody @Valid RunMigrationApiRequest request,
            Principal principal
    ) {
        UUID userId = resolveUserId(request.userId(), principal);
        ApprovedMigrationExecutionRequest domainReq = MigrationExecutionApiMapper.toDomainRequest(request, userId);

        MigrationExecutionSummary summary = jobDispatcher.dispatchMigration(domainReq, userId);
        MigrationSubmissionResponse response = MigrationSubmissionResponse.from(summary);

        log.info("Migration submission accepted: executionId={}, userId={}, reviewSessionId={}, status={}",
                summary.executionId(), userId, request.reviewSessionId(), summary.status());

        HttpStatus status = summary.status() == MigrationExecutionStatus.COMPLETED
                ? HttpStatus.OK
                : HttpStatus.ACCEPTED;

        return ResponseEntity.status(status)
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
        UUID userId = resolveUserId(null, principal);
        rateLimiter.checkLimit(userId);
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
        UUID userId = resolveUserId(null, principal);
        rateLimiter.checkLimit(userId);
        MigrationProgressSnapshot snapshot = progressService.getProgressByReviewSession(reviewSessionId, userId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(MigrationProgressResponse.from(snapshot));
    }

    /**
     * Retrieves paginated persistent migration history for the authenticated user (Phase 14C.1).
     * Ordered newest first (createdAt DESC), with optional lifecycle status filtering.
     *
     * @param status optional status filter
     * @param pageable pagination parameters (defaults to createdAt DESC)
     * @param principal authenticated principal
     * @return paginated migration summary history envelope
     */
    @GetMapping({"", "/history"})
    public ResponseEntity<PagedResponse<MigrationHistoryItemResponse>> getMigrationHistory(
            @RequestParam(required = false) MigrationExecutionStatus status,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            Principal principal
    ) {
        UUID userId = resolveUserId(null, principal);
        if (executionRepository == null) {
            return ResponseEntity.ok(new PagedResponse<>(Collections.emptyList(), 0, 0, 0L, 0, true, true));
        }

        int clampedPageSize = Math.min(Math.max(pageable.getPageSize(), 1), 100);
        int clampedPageNumber = Math.max(pageable.getPageNumber(), 0);
        Sort sort = pageable.getSort().isUnsorted() ? Sort.by(Sort.Direction.DESC, "createdAt") : pageable.getSort();
        Pageable effectivePageable = PageRequest.of(clampedPageNumber, clampedPageSize, sort);

        Page<MigrationExecution> page;
        if (status != null) {
            page = executionRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status, effectivePageable);
        } else {
            page = executionRepository.findByUserIdOrderByCreatedAtDesc(userId, effectivePageable);
        }

        PagedResponse<MigrationHistoryItemResponse> response = PagedResponse.from(
                page,
                MigrationExecutionApiMapper::toHistoryItemResponse
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves detailed migration execution record or legacy migration job by ID (Phase 14C.2).
     * Enforces strict anti-IDOR security: returns 404 NOT FOUND if nonexistent or belonging to another user.
     *
     * @param id execution or job identifier
     * @param principal authenticated principal
     * @return detailed execution response or legacy job response
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getMigration(
            @PathVariable UUID id,
            Principal principal
    ) {
        UUID userId = resolveUserId(null, principal);

        // 1. Authoritative MigrationExecution aggregate (Phases 11-14)
        if (executionRepository != null) {
            Optional<MigrationExecution> executionOpt = executionRepository.findById(id);
            if (executionOpt.isPresent()) {
                MigrationExecution execution = executionOpt.get();
                if (!execution.getUser().getId().equals(userId)) {
                    // Anti-IDOR: Prevent disclosure of foreign migration existence
                    throw new ResourceNotFoundException("Migration execution not found: " + id);
                }
                return ResponseEntity.ok(MigrationExecutionApiMapper.toExecutionResponse(execution));
            }
        }

        // 2. Legacy MigrationJob fallback (Phases 1-8)
        try {
            var job = migrationService.getMigrationJobById(id);
            if (job != null) {
                if (job.getUser() != null && job.getUser().getId() != null && !job.getUser().getId().equals(userId)) {
                    throw new ResourceNotFoundException("Migration not found: " + id);
                }
                return ResponseEntity.ok(MigrationMapper.toJobResponse(job));
            }
        } catch (ResourceNotFoundException e) {
            // Fall through to 404
        }

        throw new ResourceNotFoundException("Migration execution not found: " + id);
    }

    /**
     * Retrieves the chronological audit timeline for a migration execution (Phase 14C.3 & 14D).
     * Enforces strict anti-IDOR security: returns 404 NOT FOUND if nonexistent or belonging to another user.
     *
     * @param id execution identifier
     * @param pageable pagination parameters (defaults to createdAt ASC)
     * @param principal authenticated principal
     * @return paginated chronological audit event timeline
     */
    @GetMapping("/{id}/history")
    public ResponseEntity<PagedResponse<MigrationAuditEventResponse>> getMigrationAuditHistory(
            @PathVariable UUID id,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable,
            Principal principal
    ) {
        UUID userId = resolveUserId(null, principal);
        if (auditService == null) {
            throw new ResourceNotFoundException("Execution audit service is not configured");
        }

        int clampedPageSize = Math.min(Math.max(pageable.getPageSize(), 1), 100);
        int clampedPageNumber = Math.max(pageable.getPageNumber(), 0);
        Sort sort = pageable.getSort().isUnsorted() ? Sort.by(Sort.Direction.ASC, "createdAt") : pageable.getSort();
        Pageable effectivePageable = PageRequest.of(clampedPageNumber, clampedPageSize, sort);

        Page<ExecutionAuditEvent> page = auditService.getAuditTimeline(id, userId, effectivePageable);
        PagedResponse<MigrationAuditEventResponse> response = PagedResponse.from(
                page,
                MigrationExecutionApiMapper::toAuditEventResponse
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves track-level execution outcomes for a migration execution (Phase 14E).
     * Enforces strict anti-IDOR security: returns 404 NOT FOUND if nonexistent or belonging to another user.
     *
     * @param id execution identifier
     * @param principal authenticated principal
     * @return itemized list of track execution records in canonical target position order
     */
    @GetMapping("/{id}/tracks")
    public ResponseEntity<List<ExecutionTrackRecordResponse>> getMigrationTracks(
            @PathVariable UUID id,
            Principal principal
    ) {
        UUID userId = resolveUserId(null, principal);
        if (executionRepository == null) {
            throw new ResourceNotFoundException("Migration execution not found: " + id);
        }

        MigrationExecution execution = executionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Migration execution not found: " + id));

        if (!execution.getUser().getId().equals(userId)) {
            // Anti-IDOR: Prevent disclosure of foreign migration existence
            throw new ResourceNotFoundException("Migration execution not found: " + id);
        }

        List<ExecutionTrackRecord> records;
        if (trackRecordRepository != null) {
            records = trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(id);
        } else {
            records = execution.getTrackRecords();
        }

        List<ExecutionTrackRecordResponse> response = records.stream()
                .map(ExecutionTrackRecordDto::from)
                .map(MigrationExecutionApiMapper::toTrackRecordResponse)
                .toList();

        return ResponseEntity.ok(response);
    }

    /**
     * Resumes or recovers an eligible migration execution (Phase 14E).
     * <p>
     * Skips already ADDED tracks, reuses existing destination playlist, prevents duplicate execution,
     * updates summary counters and timestamps, and enforces anti-IDOR security.
     * Rejects already COMPLETED migrations (409 Conflict) and concurrent executions (409 Conflict).
     *
     * @param id execution identifier
     * @param principal authenticated principal
     * @return updated migration execution response
     */
    @PostMapping("/{id}/resume")
    public ResponseEntity<MigrationExecutionResponse> resumeMigration(
            @PathVariable UUID id,
            Principal principal
    ) {
        UUID userId = resolveUserId(null, principal);
        if (executionStateService == null) {
            throw new IllegalStateException("MigrationExecutionStateService is not configured");
        }

        if (executionRepository == null) {
            throw new ResourceNotFoundException("Migration execution not found: " + id);
        }

        MigrationExecution execution = executionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Migration execution not found: " + id));

        if (!execution.getUser().getId().equals(userId)) {
            // Anti-IDOR: Prevent disclosure of foreign migration existence
            throw new ResourceNotFoundException("Migration execution not found: " + id);
        }

        if (execution.getStatus() == MigrationExecutionStatus.RUNNING) {
            throw new InvalidStateException("Migration execution is already in progress");
        }

        if (execution.getStatus() == MigrationExecutionStatus.COMPLETED) {
            throw new InvalidStateException("Cannot resume: execution is already COMPLETED");
        }

        if (execution.getStatus() == MigrationExecutionStatus.ROLLED_BACK) {
            throw new InvalidStateException("Cannot resume: execution has been ROLLED_BACK");
        }

        if (execution.getUncertainInsertions() > 0) {
            throw new InvalidStateException("Cannot resume: execution contains uncertain tracks that require reconciliation");
        }

        MigrationExecutionSummary summary;
        if (execution.canResume()) {
            summary = executionStateService.resumeExecution(id, userId);
        } else if (execution.getStatus() == MigrationExecutionStatus.FAILED
                || execution.getStatus() == MigrationExecutionStatus.PARTIALLY_COMPLETED
                || execution.getStatus() == MigrationExecutionStatus.RECOVERY_REQUIRED) {
            summary = executionStateService.recoverExecution(id, userId);
        } else {
            throw new InvalidStateException("Execution cannot be resumed in current status: " + execution.getStatus());
        }

        log.info("Migration resume accepted: executionId={}, userId={}, previousStatus={}", id, userId, execution.getStatus());

        return ResponseEntity.ok(MigrationExecutionApiMapper.toExecutionResponse(summary));
    }

    @GetMapping("/{id}/tasks")
    public ResponseEntity<List<MigrationTaskResponse>> getMigrationTasks(
            @PathVariable UUID id,
            Principal principal
    ) {
        if (principal != null) {
            try {
                UUID userId = resolveUserId(null, principal);
                var job = migrationService.getMigrationJobById(id);
                if (job.getUser() != null && job.getUser().getId() != null && !job.getUser().getId().equals(userId)) {
                    throw new ResourceNotFoundException("Migration not found: " + id);
                }
            } catch (ResourceNotFoundException e) {
                if (e.getMessage() != null && e.getMessage().contains("Migration not found")) {
                    throw e;
                }
            }
        }
        var tasks = migrationService.getMigrationTasks(id);
        return ResponseEntity.ok(MigrationMapper.toTaskResponseList(tasks));
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<MigrationJobResponse> executeMigration(
            @PathVariable UUID id,
            Principal principal
    ) {
        if (principal != null) {
            try {
                UUID userId = resolveUserId(null, principal);
                var job = migrationService.getMigrationJobById(id);
                if (job.getUser() != null && job.getUser().getId() != null && !job.getUser().getId().equals(userId)) {
                    throw new ResourceNotFoundException("Migration not found: " + id);
                }
            } catch (ResourceNotFoundException e) {
                if (e.getMessage() != null && e.getMessage().contains("Migration not found")) {
                    throw e;
                }
            }
        }
        var job = migrationService.executeMigration(id);
        return ResponseEntity.ok(MigrationMapper.toJobResponse(job));
    }

    private ResponseEntity<MigrationSubmissionResponse> submitAsyncMigration(JsonNode payload, Principal principal) {
        RunMigrationApiRequest request;
        try {
            request = objectMapper.treeToValue(payload, RunMigrationApiRequest.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed approved migration request: " + e.getMessage(), e);
        }

        if (request == null || request.reviewSessionId() == null) {
            throw new IllegalArgumentException("reviewSessionId is required");
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

        log.info("Migration submission accepted: executionId={}, userId={}, reviewSessionId={}, status={}",
                summary.executionId(), userId, request.reviewSessionId(), summary.status());

        HttpStatus status = summary.status() == MigrationExecutionStatus.COMPLETED
                ? HttpStatus.OK
                : HttpStatus.ACCEPTED;

        log.info("Accepted approved migration submission for review session {} -> executionId={}, status={}",
                request.reviewSessionId(), response.executionId(), status);

        return ResponseEntity.status(status)
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
        if (principal == null || principal.getName() == null || principal.getName().isBlank()
                || "anonymousUser".equalsIgnoreCase(principal.getName())) {
            throw new InsufficientAuthenticationException("User must be authenticated");
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

