package com.spotifyyoutube.migrator.migration.api;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.application.UserService;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.api.dto.CreateDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.api.dto.DestinationPlaylistResponse;
import com.spotifyyoutube.migrator.migration.api.dto.ExecutionValidationResponse;
import com.spotifyyoutube.migrator.migration.api.dto.InitializeExecutionRequest;
import com.spotifyyoutube.migrator.migration.api.dto.InsertTracksRequest;
import com.spotifyyoutube.migrator.migration.api.dto.MigrationExecutionResponse;
import com.spotifyyoutube.migrator.migration.api.dto.PlaylistTrackInsertionResponse;
import com.spotifyyoutube.migrator.migration.api.dto.RecoverExecutionApiRequest;
import com.spotifyyoutube.migrator.migration.api.dto.RollbackExecutionApiRequest;
import com.spotifyyoutube.migrator.migration.api.dto.ExecutionReconciliationStatusResponse;
import com.spotifyyoutube.migrator.migration.api.dto.ReconcileExecutionApiRequest;
import com.spotifyyoutube.migrator.migration.api.dto.ReconcileExecutionResponse;
import com.spotifyyoutube.migrator.migration.api.dto.ReconciliationRecordResponse;
import com.spotifyyoutube.migrator.migration.api.dto.RollbackExecutionResponse;
import com.spotifyyoutube.migrator.migration.api.dto.RollbackRecordResponse;
import com.spotifyyoutube.migrator.migration.api.dto.ValidateExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionReconciliationStatusDto;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionReconciliationSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ReconcileExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ReconciliationRecordDto;
import com.spotifyyoutube.migrator.migration.api.mapper.MigrationExecutionApiMapper;
import com.spotifyyoutube.migrator.migration.application.execution.ApprovedMigrationExecutionService;
import com.spotifyyoutube.migrator.migration.application.execution.DestinationPlaylistCreationService;
import com.spotifyyoutube.migrator.migration.application.execution.MigrationExecutionStateService;
import com.spotifyyoutube.migrator.migration.application.execution.OrderedTrackInsertionService;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.DestinationPlaylistCreationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionRollbackSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.InsertTracksExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.PlaylistTrackInsertionSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RecoverExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackRecordDto;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST controller exposing execution validation, eligibility inspection, destination playlist creation,
 * ordered track insertion, and durable execution state management for approved migrations.
 * <p>
 * Enforces security ownership, anti-impersonation, strict validation boundaries, optimistic locking,
 * and deterministic safe resume semantics.
 */
@RestController
@RequestMapping({"/api/migrations/execution", "/api/migrations/approved"})
public class MigrationExecutionController {

    private final ApprovedMigrationExecutionService executionService;
    private final DestinationPlaylistCreationService playlistCreationService;
    private final OrderedTrackInsertionService trackInsertionService;
    private final MigrationExecutionStateService executionStateService;
    private final UserService userService;

    @Autowired
    public MigrationExecutionController(
            ApprovedMigrationExecutionService executionService,
            DestinationPlaylistCreationService playlistCreationService,
            OrderedTrackInsertionService trackInsertionService,
            MigrationExecutionStateService executionStateService,
            UserService userService
    ) {
        this.executionService = Objects.requireNonNull(executionService, "ApprovedMigrationExecutionService must not be null");
        this.playlistCreationService = playlistCreationService;
        this.trackInsertionService = trackInsertionService;
        this.executionStateService = executionStateService;
        this.userService = Objects.requireNonNull(userService, "UserService must not be null");
    }

    public MigrationExecutionController(
            ApprovedMigrationExecutionService executionService,
            DestinationPlaylistCreationService playlistCreationService,
            OrderedTrackInsertionService trackInsertionService,
            UserService userService
    ) {
        this(executionService, playlistCreationService, trackInsertionService, null, userService);
    }

    public MigrationExecutionController(
            ApprovedMigrationExecutionService executionService,
            DestinationPlaylistCreationService playlistCreationService,
            UserService userService
    ) {
        this(executionService, playlistCreationService, null, null, userService);
    }

    public MigrationExecutionController(
            ApprovedMigrationExecutionService executionService,
            UserService userService
    ) {
        this(executionService, null, null, null, userService);
    }

    /**
     * Validates an approved migration execution request against security context and domain rules.
     */
    @PostMapping("/validate")
    public ResponseEntity<ExecutionValidationResponse> validateExecution(
            @RequestBody @Valid ValidateExecutionRequest request,
            Principal principal
    ) {
        UUID userId = resolveUserId(request.userId(), principal);
        ApprovedMigrationExecutionRequest domainReq = MigrationExecutionApiMapper.toDomainRequest(request, userId);
        ExecutionValidationResult result = executionService.validateExecution(domainReq, userId);
        return ResponseEntity.ok(MigrationExecutionApiMapper.toResponse(request.reviewSessionId(), result));
    }

    /**
     * Creates a destination playlist using the validated approved migration context (Phase 11C).
     * Strictly creates the playlist only without inserting tracks.
     */
    @PostMapping("/create-playlist")
    public ResponseEntity<DestinationPlaylistResponse> createDestinationPlaylist(
            @RequestBody @Valid CreateDestinationPlaylistRequest request,
            Principal principal
    ) {
        if (playlistCreationService == null) {
            throw new IllegalStateException("DestinationPlaylistCreationService is not configured");
        }
        UUID userId = resolveUserId(request.userId(), principal);
        ApprovedMigrationExecutionRequest domainReq = MigrationExecutionApiMapper.toDomainRequest(request, userId);
        DestinationPlaylistCreationResult result = playlistCreationService.createDestinationPlaylist(domainReq, userId);
        return ResponseEntity.ok(MigrationExecutionApiMapper.toPlaylistResponse(result));
    }

    /**
     * Convenience endpoint to create a destination playlist by review session ID (Phase 11C).
     */
    @PostMapping("/create-playlist/{sessionId}")
    public ResponseEntity<DestinationPlaylistResponse> createDestinationPlaylistBySessionId(
            @PathVariable UUID sessionId,
            Principal principal
    ) {
        if (playlistCreationService == null) {
            throw new IllegalStateException("DestinationPlaylistCreationService is not configured");
        }
        UUID userId = resolveUserId(null, principal);
        DestinationPlaylistCreationResult result = playlistCreationService.createDestinationPlaylist(sessionId, userId);
        return ResponseEntity.ok(MigrationExecutionApiMapper.toPlaylistResponse(result));
    }

    /**
     * Inspects the execution eligibility of an approved review session by ID.
     */
    @GetMapping("/eligibility/{sessionId}")
    public ResponseEntity<ExecutionValidationResponse> checkEligibility(
            @PathVariable UUID sessionId,
            Principal principal
    ) {
        UUID userId = resolveUserId(null, principal);
        ExecutionValidationResult result = executionService.validateExecution(sessionId, userId);
        return ResponseEntity.ok(MigrationExecutionApiMapper.toResponse(sessionId, result));
    }

    /**
     * Inserts approved tracks into the specified destination playlist in canonical approved order (Phase 11D).
     */
    @PostMapping("/insert-tracks")
    public ResponseEntity<PlaylistTrackInsertionResponse> insertApprovedTracks(
            @RequestBody @Valid InsertTracksRequest request,
            Principal principal
    ) {
        if (trackInsertionService == null) {
            throw new IllegalStateException("OrderedTrackInsertionService is not configured");
        }
        UUID userId = resolveUserId(request.userId(), principal);
        InsertTracksExecutionRequest domainReq = MigrationExecutionApiMapper.toDomainRequest(request, userId);
        PlaylistTrackInsertionSummary summary = trackInsertionService.insertApprovedTracks(domainReq, userId);
        return ResponseEntity.ok(MigrationExecutionApiMapper.toInsertionResponse(summary));
    }

    /**
     * Convenience endpoint to insert approved tracks into a destination playlist by review session ID (Phase 11D).
     */
    @PostMapping("/insert-tracks/{sessionId}")
    public ResponseEntity<PlaylistTrackInsertionResponse> insertApprovedTracksBySessionId(
            @PathVariable UUID sessionId,
            @RequestParam String destinationPlaylistId,
            Principal principal
    ) {
        if (trackInsertionService == null) {
            throw new IllegalStateException("OrderedTrackInsertionService is not configured");
        }
        UUID userId = resolveUserId(null, principal);
        InsertTracksExecutionRequest domainReq = new InsertTracksExecutionRequest(sessionId, destinationPlaylistId, userId);
        PlaylistTrackInsertionSummary summary = trackInsertionService.insertApprovedTracks(domainReq, userId);
        return ResponseEntity.ok(MigrationExecutionApiMapper.toInsertionResponse(summary));
    }

    /**
     * Initializes durable execution state for an approved migration with a confirmed destination playlist (Phase 11E).
     * Idempotent: repeated calls with the same reviewSessionId and destinationPlaylistId return the existing execution record.
     */
    @PostMapping("/initialize")
    public ResponseEntity<MigrationExecutionResponse> initializeExecution(
            @RequestBody @Valid InitializeExecutionRequest request,
            Principal principal
    ) {
        if (executionStateService == null) {
            throw new IllegalStateException("MigrationExecutionStateService is not configured");
        }
        UUID userId = resolveUserId(request.userId(), principal);
        var domainReq = MigrationExecutionApiMapper.toDomainRequest(request, userId);
        MigrationExecutionSummary summary = executionStateService.initializeExecution(domainReq, userId);
        return ResponseEntity.ok(MigrationExecutionApiMapper.toExecutionResponse(summary));
    }

    /**
     * Retrieves the durable execution state and track records by execution ID (Phase 11E).
     */
    @GetMapping("/{executionId}")
    public ResponseEntity<MigrationExecutionResponse> getExecution(
            @PathVariable UUID executionId,
            Principal principal
    ) {
        if (executionStateService == null) {
            throw new IllegalStateException("MigrationExecutionStateService is not configured");
        }
        UUID userId = resolveUserId(null, principal);
        MigrationExecutionSummary summary = executionStateService.getExecution(executionId, userId);
        return ResponseEntity.ok(MigrationExecutionApiMapper.toExecutionResponse(summary));
    }

    /**
     * Retrieves the durable execution state by review session ID (Phase 11E).
     */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<MigrationExecutionResponse> getExecutionByReviewSession(
            @PathVariable UUID sessionId,
            Principal principal
    ) {
        if (executionStateService == null) {
            throw new IllegalStateException("MigrationExecutionStateService is not configured");
        }
        UUID userId = resolveUserId(null, principal);
        MigrationExecutionSummary summary = executionStateService.getExecutionByReviewSession(sessionId, userId);
        return ResponseEntity.ok(MigrationExecutionApiMapper.toExecutionResponse(summary));
    }

    /**
     * Resumes execution of pending tracks for an eligible migration execution (Phase 11E).
     * Never re-inserts ADDED tracks; executes pending tracks in canonical order.
     */
    @PostMapping("/{executionId}/resume")
    public ResponseEntity<MigrationExecutionResponse> resumeExecution(
            @PathVariable UUID executionId,
            Principal principal
    ) {
        if (executionStateService == null) {
            throw new IllegalStateException("MigrationExecutionStateService is not configured");
        }
        UUID userId = resolveUserId(null, principal);
        MigrationExecutionSummary summary = executionStateService.resumeExecution(executionId, userId);
        return ResponseEntity.ok(MigrationExecutionApiMapper.toExecutionResponse(summary));
    }

    /**
     * Recovers eligible failed tracks for an execution and executes insertion (Phase 11F).
     * Strictly preserves ADDED tracks, maintains canonical order, and rejects executions with UNCERTAIN tracks.
     */
    @PostMapping("/{executionId}/recover")
    public ResponseEntity<MigrationExecutionResponse> recoverExecution(
            @PathVariable UUID executionId,
            @RequestBody(required = false) RecoverExecutionApiRequest request,
            Principal principal
    ) {
        if (executionStateService == null) {
            throw new IllegalStateException("MigrationExecutionStateService is not configured");
        }
        UUID requestedUserId = request != null ? request.userId() : null;
        UUID userId = resolveUserId(requestedUserId, principal);
        RecoverExecutionRequest domainReq = MigrationExecutionApiMapper.toDomainRequest(request, userId);
        MigrationExecutionSummary summary = executionStateService.recoverExecution(executionId, domainReq, userId);
        return ResponseEntity.ok(MigrationExecutionApiMapper.toExecutionResponse(summary));
    }

    /**
     * Partially rolls back confirmed inserted tracks from the destination playlist (Phase 11F).
     * Performs remote playlistItem deletion outside database transactions, updates state,
     * and persists audit records. Idempotent on already rolled-back items.
     */
    @PostMapping("/{executionId}/rollback")
    public ResponseEntity<RollbackExecutionResponse> rollbackExecution(
            @PathVariable UUID executionId,
            @RequestBody @Valid RollbackExecutionApiRequest request,
            Principal principal
    ) {
        if (executionStateService == null) {
            throw new IllegalStateException("MigrationExecutionStateService is not configured");
        }
        UUID userId = resolveUserId(request.userId(), principal);
        RollbackExecutionRequest domainReq = MigrationExecutionApiMapper.toDomainRequest(request, userId);
        ExecutionRollbackSummary summary = executionStateService.rollbackExecution(executionId, domainReq, userId);
        return ResponseEntity.ok(MigrationExecutionApiMapper.toRollbackResponse(summary));
    }

    /**
     * Retrieves the audit history of all rollback attempts for an execution (Phase 11F).
     */
    @GetMapping({"/{executionId}/rollbacks", "/{executionId}/rollback-history"})
    public ResponseEntity<List<RollbackRecordResponse>> getRollbackHistory(
            @PathVariable UUID executionId,
            Principal principal
    ) {
        if (executionStateService == null) {
            throw new IllegalStateException("MigrationExecutionStateService is not configured");
        }
        UUID userId = resolveUserId(null, principal);
        List<RollbackRecordDto> records = executionStateService.getRollbackHistory(executionId, userId);
        List<RollbackRecordResponse> response = records.stream()
                .map(MigrationExecutionApiMapper::toRollbackRecordResponse)
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * Reconciles uncertain track outcomes for an execution against the remote destination playlist (Phase 11G).
     * Inspects destination playlist, verifies remote mutations, updates state, and persists audit records.
     */
    @PostMapping("/{executionId}/reconcile")
    public ResponseEntity<ReconcileExecutionResponse> reconcileExecution(
            @PathVariable UUID executionId,
            @RequestBody(required = false) ReconcileExecutionApiRequest request,
            Principal principal
    ) {
        if (executionStateService == null) {
            throw new IllegalStateException("MigrationExecutionStateService is not configured");
        }
        UUID requestedUserId = request != null ? request.userId() : null;
        UUID userId = resolveUserId(requestedUserId, principal);
        ReconcileExecutionRequest domainReq = MigrationExecutionApiMapper.toDomainRequest(request, userId);
        ExecutionReconciliationSummary summary = executionStateService.reconcileExecution(executionId, domainReq, userId);
        return ResponseEntity.ok(MigrationExecutionApiMapper.toReconcileResponse(summary));
    }

    /**
     * Retrieves the audit history of all reconciliation attempts for an execution (Phase 11G).
     */
    @GetMapping({"/{executionId}/reconciliations", "/{executionId}/reconciliation-history"})
    public ResponseEntity<List<ReconciliationRecordResponse>> getReconciliationHistory(
            @PathVariable UUID executionId,
            Principal principal
    ) {
        if (executionStateService == null) {
            throw new IllegalStateException("MigrationExecutionStateService is not configured");
        }
        UUID userId = resolveUserId(null, principal);
        List<ReconciliationRecordDto> records = executionStateService.getReconciliationHistory(executionId, userId);
        List<ReconciliationRecordResponse> response = records.stream()
                .map(MigrationExecutionApiMapper::toReconciliationRecordResponse)
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves the current reconciliation status and eligible uncertain tracks for an execution (Phase 11G).
     */
    @GetMapping("/{executionId}/reconciliation-status")
    public ResponseEntity<ExecutionReconciliationStatusResponse> getReconciliationStatus(
            @PathVariable UUID executionId,
            Principal principal
    ) {
        if (executionStateService == null) {
            throw new IllegalStateException("MigrationExecutionStateService is not configured");
        }
        UUID userId = resolveUserId(null, principal);
        ExecutionReconciliationStatusDto status = executionStateService.getReconciliationStatus(executionId, userId);
        return ResponseEntity.ok(MigrationExecutionApiMapper.toReconciliationStatusResponse(status));
    }

    private UUID resolveUserId(UUID requestedUserId, Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new IllegalArgumentException("User must be authenticated");
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
