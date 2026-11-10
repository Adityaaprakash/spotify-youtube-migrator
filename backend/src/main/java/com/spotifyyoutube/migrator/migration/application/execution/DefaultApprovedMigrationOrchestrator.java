package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.migration.application.execution.contract.InitializeExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.DestinationPlaylistCreationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionReconciliationSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionRollbackSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionValidationException;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ReconcileExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RecoverExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ValidationFailureCode;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.MigrationIdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Default production implementation of {@link ApprovedMigrationOrchestrator}.
 * <p>
 * Coordinates approved plan validation, destination playlist creation, durable execution initialization,
 * and ordered track insertion into a unified, coherent workflow while strictly preserving individual subsystem
 * transaction boundaries, idempotency invariants, and security ownership.
 */
@Service
public class DefaultApprovedMigrationOrchestrator implements ApprovedMigrationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DefaultApprovedMigrationOrchestrator.class);

    private final ApprovedMigrationExecutionService executionService;
    private final DestinationPlaylistCreationService playlistCreationService;
    private final MigrationExecutionStateService executionStateService;
    private final MigrationIdempotencyService idempotencyService;

    public DefaultApprovedMigrationOrchestrator(
            ApprovedMigrationExecutionService executionService,
            DestinationPlaylistCreationService playlistCreationService,
            MigrationExecutionStateService executionStateService
    ) {
        this(executionService, playlistCreationService, executionStateService, null);
    }

    @Autowired
    public DefaultApprovedMigrationOrchestrator(
            ApprovedMigrationExecutionService executionService,
            DestinationPlaylistCreationService playlistCreationService,
            MigrationExecutionStateService executionStateService,
            @Autowired(required = false) MigrationIdempotencyService idempotencyService
    ) {
        this.executionService = Objects.requireNonNull(executionService, "ApprovedMigrationExecutionService must not be null");
        this.playlistCreationService = Objects.requireNonNull(playlistCreationService, "DestinationPlaylistCreationService must not be null");
        this.executionStateService = Objects.requireNonNull(executionStateService, "MigrationExecutionStateService must not be null");
        this.idempotencyService = idempotencyService;
    }

    @Override
    public MigrationExecutionSummary executeApprovedMigration(ApprovedMigrationExecutionRequest request, UUID authenticatedUserId) {
        if (request == null) {
            throw new IllegalArgumentException("ApprovedMigrationExecutionRequest must not be null");
        }
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user ID must not be null");
        }

        UUID sessionId = request.reviewSessionId();
        log.info("Starting approved migration orchestration for review session {} by user {}", sessionId, authenticatedUserId);

        // Step 1: Pre-mutation validated execution context (Phase 11B)
        ExecutionValidationResult validationResult = executionService.validateExecution(request, authenticatedUserId);
        if (!validationResult.isValid()) {
            if (isSessionNotFoundOrUnauthorized(validationResult)) {
                throw new ResourceNotFoundException("Review session not found with id: " + sessionId);
            }
            log.warn("Approved migration execution rejected by validator for review session {}: {}",
                    sessionId, validationResult.errors());
            throw new MigrationExecutionValidationException("Approved migration execution validation failed", validationResult);
        }

        // Step 2: Check for existing durable execution state (Phase 11E Idempotency)
        MigrationExecutionSummary existing = null;
        try {
            existing = executionStateService.getExecutionByReviewSession(sessionId, authenticatedUserId);
        } catch (ResourceNotFoundException e) {
            // No prior execution state exists; normal initial execution path
        }

        if (existing != null) {
            log.info("Existing durable execution {} found in status {} for review session {}",
                    existing.executionId(), existing.status(), sessionId);

            if (existing.status() == MigrationExecutionStatus.COMPLETED) {
                log.info("Execution {} is already COMPLETED. Returning existing summary without redundant mutations.",
                        existing.executionId());
                return existing;
            }

            if (existing.status() == MigrationExecutionStatus.RUNNING) {
                throw new InvalidStateException("Migration execution is currently in progress: " + existing.executionId());
            }

            if (existing.status() == MigrationExecutionStatus.RECOVERY_REQUIRED) {
                throw new InvalidStateException("Migration execution requires reconciliation or recovery before proceeding: "
                        + existing.executionId());
            }

            // If execution is in INITIALIZED, PARTIALLY_COMPLETED, or FAILED and can resume pending tracks
            if (existing.canResume()) {
                log.info("Resuming execution {} from current status {}", existing.executionId(), existing.status());
                return executionStateService.resumeExecution(existing.executionId(), authenticatedUserId);
            }
        }

        // Step 3: Destination Playlist Creation (Phase 11C)
        // If an execution already exists, reuse its playlist ID; otherwise create a new playlist
        String destinationPlaylistId;
        if (existing != null) {
            destinationPlaylistId = existing.destinationPlaylistId();
        } else {
            log.info("Creating destination playlist on YouTube for review session {}", sessionId);
            DestinationPlaylistCreationResult playlistResult = playlistCreationService.createDestinationPlaylist(request, authenticatedUserId);
            destinationPlaylistId = playlistResult.destinationPlaylistId();
            log.info("Destination playlist created successfully: {}", destinationPlaylistId);
        }

        // Step 4: Durable Execution Initialization (Phase 11E)
        InitializeExecutionRequest initReq = InitializeExecutionRequest.of(
                sessionId,
                destinationPlaylistId,
                authenticatedUserId
        );
        MigrationExecutionSummary initSummary = executionStateService.initializeExecution(initReq, authenticatedUserId);
        UUID executionId = initSummary.executionId();

        // Step 5: Ordered Sequential Track Insertion (Phase 11D / 11E)
        log.info("Executing ordered track insertion for execution {} into playlist {}", executionId, destinationPlaylistId);
        MigrationExecutionSummary finalSummary = executionStateService.executeTrackInsertion(executionId, authenticatedUserId);
        log.info("Migration execution {} completed with status: {}", executionId, finalSummary.status());

        return finalSummary;
    }

    @Override
    public MigrationExecutionSummary prepareQueuedMigration(ApprovedMigrationExecutionRequest request, UUID authenticatedUserId) {
        if (request == null) {
            throw new IllegalArgumentException("ApprovedMigrationExecutionRequest must not be null");
        }
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user ID must not be null");
        }

        UUID sessionId = request.reviewSessionId();
        log.info("Preparing queued migration for review session {} by user {}", sessionId, authenticatedUserId);

        // Step 1: Pre-mutation validated execution context (Phase 11B)
        ExecutionValidationResult validationResult = executionService.validateExecution(request, authenticatedUserId);
        if (!validationResult.isValid()) {
            if (isSessionNotFoundOrUnauthorized(validationResult)) {
                throw new ResourceNotFoundException("Review session not found with id: " + sessionId);
            }
            log.warn("Approved migration rejected by validator for review session {}: {}",
                    sessionId, validationResult.errors());
            throw new MigrationExecutionValidationException("Approved migration execution validation failed", validationResult);
        }

        // Step 2: Check for existing durable execution state (Idempotency)
        MigrationExecutionSummary existing = null;
        try {
            existing = executionStateService.getExecutionByReviewSession(sessionId, authenticatedUserId);
        } catch (ResourceNotFoundException e) {
            // Normal initial execution path
        }

        if (existing != null) {
            log.info("Existing durable execution {} found in status {} for review session {}",
                    existing.executionId(), existing.status(), sessionId);

            if (existing.status() == MigrationExecutionStatus.COMPLETED) {
                log.info("Execution {} is already COMPLETED. Returning existing summary.", existing.executionId());
                return existing;
            }

            if (existing.status() == MigrationExecutionStatus.RUNNING) {
                throw new InvalidStateException("Migration execution is currently in progress: " + existing.executionId());
            }

            if (existing.status() == MigrationExecutionStatus.QUEUED) {
                throw new InvalidStateException("Migration execution is already queued: " + existing.executionId());
            }

            if (existing.status() == MigrationExecutionStatus.RECOVERY_REQUIRED) {
                throw new InvalidStateException("Migration execution requires reconciliation or recovery before proceeding: "
                        + existing.executionId());
            }
        }

        // Step 3: Destination Playlist Creation (Phase 11C)
        String destinationPlaylistId;
        String destinationPlaylistUrl = null;
        if (existing != null) {
            destinationPlaylistId = existing.destinationPlaylistId();
            destinationPlaylistUrl = existing.destinationPlaylistUrl();
        } else {
            // Signal that external operation is starting to prevent blind retries across process crashes
            if (idempotencyService != null) {
                idempotencyService.markExternalOperationStartedForSession(sessionId);
            }
            log.info("Creating destination playlist on YouTube for review session {}", sessionId);
            DestinationPlaylistCreationResult playlistResult = playlistCreationService.createDestinationPlaylist(request, authenticatedUserId);
            destinationPlaylistId = playlistResult.destinationPlaylistId();
            destinationPlaylistUrl = playlistResult.destinationPlaylistUrl();
            log.info("Destination playlist created successfully: {}", destinationPlaylistId);

            // Record allocated playlist ID durably on the pending claim before execution persistence
            if (idempotencyService != null) {
                idempotencyService.recordDestinationPlaylistForSession(sessionId, destinationPlaylistId);
            }
        }

        // Step 4: Durable Execution Initialization in QUEUED status (Phase 12B)
        InitializeExecutionRequest initReq = new InitializeExecutionRequest(
                sessionId,
                destinationPlaylistId,
                destinationPlaylistUrl,
                authenticatedUserId
        );
        MigrationExecutionSummary queuedSummary = executionStateService.initializeQueuedExecution(initReq, authenticatedUserId);
        log.info("Prepared queued migration execution {} in status {} for review session {}",
                queuedSummary.executionId(), queuedSummary.status(), sessionId);

        return queuedSummary;
    }

    @Override
    public MigrationExecutionSummary executeQueuedMigration(UUID executionId, UUID authenticatedUserId) {
        if (executionId == null) {
            throw new IllegalArgumentException("Execution ID must not be null");
        }
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user ID must not be null");
        }

        log.info("Orchestrator executing queued migration {} for user {}", executionId, authenticatedUserId);
        return executionStateService.executeQueuedTrackInsertion(executionId, authenticatedUserId);
    }

    @Override
    public MigrationExecutionSummary executeClaimedMigration(
            UUID executionId,
            UUID authenticatedUserId,
            long leaseGeneration
    ) {
        if (executionId == null) {
            throw new IllegalArgumentException("Execution ID must not be null");
        }
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user ID must not be null");
        }

        log.info("Orchestrator executing claimed migration {} under generation {} for user {}",
                executionId, leaseGeneration, authenticatedUserId);
        return executionStateService.executeClaimedTrackInsertion(executionId, authenticatedUserId, leaseGeneration);
    }

    @Override
    public MigrationExecutionSummary recoverMigration(UUID executionId, RecoverExecutionRequest request, UUID authenticatedUserId) {
        log.info("Orchestrating recovery for execution {} by user {}", executionId, authenticatedUserId);
        return executionStateService.recoverExecution(executionId, request, authenticatedUserId);
    }

    @Override
    public ExecutionRollbackSummary rollbackMigration(UUID executionId, RollbackExecutionRequest request, UUID authenticatedUserId) {
        log.info("Orchestrating rollback for execution {} by user {}", executionId, authenticatedUserId);
        return executionStateService.rollbackExecution(executionId, request, authenticatedUserId);
    }

    @Override
    public ExecutionReconciliationSummary reconcileMigration(UUID executionId, ReconcileExecutionRequest request, UUID authenticatedUserId) {
        log.info("Orchestrating reconciliation for execution {} by user {}", executionId, authenticatedUserId);
        return executionStateService.reconcileExecution(executionId, request, authenticatedUserId);
    }

    @Override
    public MigrationExecutionSummary getExecutionSummary(UUID executionId, UUID authenticatedUserId) {
        return executionStateService.getExecution(executionId, authenticatedUserId);
    }

    @Override
    public MigrationExecutionSummary getExecutionSummaryByReviewSession(UUID reviewSessionId, UUID authenticatedUserId) {
        return executionStateService.getExecutionByReviewSession(reviewSessionId, authenticatedUserId);
    }

    /**
     * Determines whether a validation failure is caused by an unauthorized access attempt
     * (cross-user IDOR) or a nonexistent review session, which must be mapped to
     * {@link ResourceNotFoundException} (HTTP 404) to prevent existence disclosure.
     */
    private boolean isSessionNotFoundOrUnauthorized(ExecutionValidationResult result) {
        if (result == null || result.errors() == null) {
            return false;
        }
        return result.errors().stream().anyMatch(error ->
                error.code() == ValidationFailureCode.UNAUTHORIZED
                || (error.code() == ValidationFailureCode.MISSING_CONTEXT && !error.isTrackLevel())
        );
    }
}
