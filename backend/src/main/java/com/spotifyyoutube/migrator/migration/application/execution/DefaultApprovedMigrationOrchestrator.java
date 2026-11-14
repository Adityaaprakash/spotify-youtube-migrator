package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylist;
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
import com.spotifyyoutube.migrator.migration.reliability.classification.ProviderFailureCategory;
import com.spotifyyoutube.migrator.migration.reliability.classification.ProviderFailureClassification;
import com.spotifyyoutube.migrator.migration.reliability.classification.ProviderFailureClassifier;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.ClaimOwnershipContext;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.MigrationIdempotencyService;
import com.spotifyyoutube.migrator.migration.reliability.idempotency.StaleClaimOwnershipException;
import com.spotifyyoutube.migrator.migration.reliability.reconciliation.DestinationPlaylistReconciliationService;
import com.spotifyyoutube.migrator.migration.reliability.reconciliation.PlaylistCorrelationMarker;
import com.spotifyyoutube.migrator.migration.reliability.reconciliation.PlaylistReconciliationOutcome;
import com.spotifyyoutube.migrator.migration.reliability.reconciliation.PlaylistReconciliationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class DefaultApprovedMigrationOrchestrator implements ApprovedMigrationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DefaultApprovedMigrationOrchestrator.class);

    private final ApprovedMigrationExecutionService executionService;
    private final DestinationPlaylistCreationService playlistCreationService;
    private final MigrationExecutionStateService executionStateService;
    private final MigrationIdempotencyService idempotencyService;
    private final DestinationPlaylistReconciliationService playlistReconciliationService;
    private final ProviderFailureClassifier providerFailureClassifier;

    public DefaultApprovedMigrationOrchestrator(
            ApprovedMigrationExecutionService executionService,
            DestinationPlaylistCreationService playlistCreationService,
            MigrationExecutionStateService executionStateService
    ) {
        this(executionService, playlistCreationService, executionStateService, null, null, null);
    }

    public DefaultApprovedMigrationOrchestrator(
            ApprovedMigrationExecutionService executionService,
            DestinationPlaylistCreationService playlistCreationService,
            MigrationExecutionStateService executionStateService,
            MigrationIdempotencyService idempotencyService
    ) {
        this(executionService, playlistCreationService, executionStateService, idempotencyService, null, null);
    }

    public DefaultApprovedMigrationOrchestrator(
            ApprovedMigrationExecutionService executionService,
            DestinationPlaylistCreationService playlistCreationService,
            MigrationExecutionStateService executionStateService,
            MigrationIdempotencyService idempotencyService,
            DestinationPlaylistReconciliationService playlistReconciliationService
    ) {
        this(executionService, playlistCreationService, executionStateService, idempotencyService, playlistReconciliationService, null);
    }

    @Autowired
    public DefaultApprovedMigrationOrchestrator(
            ApprovedMigrationExecutionService executionService,
            DestinationPlaylistCreationService playlistCreationService,
            MigrationExecutionStateService executionStateService,
            @Autowired(required = false) MigrationIdempotencyService idempotencyService,
            @Autowired(required = false) DestinationPlaylistReconciliationService playlistReconciliationService,
            @Autowired(required = false) ProviderFailureClassifier providerFailureClassifier
    ) {
        this.executionService = Objects.requireNonNull(executionService, "ApprovedMigrationExecutionService must not be null");
        this.playlistCreationService = Objects.requireNonNull(playlistCreationService, "DestinationPlaylistCreationService must not be null");
        this.executionStateService = Objects.requireNonNull(executionStateService, "MigrationExecutionStateService must not be null");
        this.idempotencyService = idempotencyService;
        this.playlistReconciliationService = playlistReconciliationService;
        this.providerFailureClassifier = providerFailureClassifier != null
                ? providerFailureClassifier
                : new ProviderFailureClassifier();
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

            if (existing.status() == MigrationExecutionStatus.ALLOCATING_DESTINATION) {
                log.info("Execution {} found in ALLOCATING_DESTINATION status. Reconciling external playlist creation.",
                        existing.executionId());
                existing = resolveAllocatingDestinationExecution(
                        existing, request, authenticatedUserId, MigrationExecutionStatus.INITIALIZED
                );
            }

            // If execution is in INITIALIZED, PARTIALLY_COMPLETED, or FAILED and can resume pending tracks
            if (existing.canResume()) {
                log.info("Resuming execution {} from current status {}", existing.executionId(), existing.status());
                return executionStateService.resumeExecution(existing.executionId(), authenticatedUserId);
            }
        }

        // Step 3 & 4: Destination Playlist Creation with Pre-allocated Operation Intent (Phase 13F)
        UUID executionId;
        String destinationPlaylistId;
        if (existing != null) {
            executionId = existing.executionId();
            destinationPlaylistId = existing.destinationPlaylistId();
        } else {
            ClaimOwnershipContext claimContext = ClaimOwnershipContext.get();
            if (claimContext != null && claimContext.isOwnershipLost()) {
                log.warn("Claim ownership lost prior to external mutation for key {}", claimContext.idempotencyKey());
                throw new StaleClaimOwnershipException(
                        claimContext.idempotencyKey(),
                        claimContext.claimOwner(),
                        claimContext.claimGeneration()
                );
            }

            UUID opId = UUID.randomUUID();
            if (idempotencyService != null) {
                idempotencyService.markExternalOperationStartedForSession(sessionId, opId);
            }

            MigrationExecutionSummary intentSummary = executionStateService.initializeExecutionIntent(
                    sessionId, opId, authenticatedUserId
            );
            executionId = intentSummary.executionId();

            DestinationPlaylistDetails details = createOrReconcileDestinationPlaylist(
                    executionId, opId, request, authenticatedUserId, MigrationExecutionStatus.INITIALIZED
            );
            destinationPlaylistId = details.playlistId();
        }

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

            if (existing.status() == MigrationExecutionStatus.ALLOCATING_DESTINATION) {
                log.info("Existing execution {} is in ALLOCATING_DESTINATION. Reconciling for QUEUED transition.",
                        existing.executionId());
                return resolveAllocatingDestinationExecution(
                        existing, request, authenticatedUserId, MigrationExecutionStatus.QUEUED
                );
            }
        }

        // Step 3: Destination Playlist Creation with Pre-allocated Operation Intent (Phase 13F)
        ClaimOwnershipContext queuedClaimContext = ClaimOwnershipContext.get();
        if (queuedClaimContext != null && queuedClaimContext.isOwnershipLost()) {
            log.warn("Claim ownership lost prior to external playlist creation for key {}", queuedClaimContext.idempotencyKey());
            throw new StaleClaimOwnershipException(
                    queuedClaimContext.idempotencyKey(),
                    queuedClaimContext.claimOwner(),
                    queuedClaimContext.claimGeneration()
            );
        }

        UUID opId = UUID.randomUUID();
        if (idempotencyService != null) {
            idempotencyService.markExternalOperationStartedForSession(sessionId, opId);
        }

        MigrationExecutionSummary intentSummary = executionStateService.initializeExecutionIntent(
                sessionId, opId, authenticatedUserId
        );
        UUID executionId = intentSummary.executionId();

        createOrReconcileDestinationPlaylist(
                executionId, opId, request, authenticatedUserId, MigrationExecutionStatus.QUEUED
        );

        MigrationExecutionSummary queuedSummary = executionStateService.getExecution(executionId, authenticatedUserId);
        log.info("Prepared queued migration execution {} in status {} for review session {}",
                queuedSummary.executionId(), queuedSummary.status(), sessionId);

        return queuedSummary;
    }

    private DestinationPlaylistDetails createOrReconcileDestinationPlaylist(
            UUID executionId,
            UUID opId,
            ApprovedMigrationExecutionRequest request,
            UUID authenticatedUserId,
            MigrationExecutionStatus nextStatus
    ) {
        String baseDesc = request.getTargetPlaylistDescription().orElse("Migrated from Spotify");
        String markedDesc = PlaylistCorrelationMarker.applyMarker(baseDesc, opId);
        ApprovedMigrationExecutionRequest markedRequest = new ApprovedMigrationExecutionRequest(
                request.reviewSessionId(),
                request.getUserId().orElse(authenticatedUserId),
                request.getSourcePlaylistId().orElse(null),
                request.getPlanId().orElse(null),
                request.getTargetPlaylistTitle().orElse(null),
                markedDesc,
                request.isPrivate(),
                request.getPlan().orElse(null)
        );

        ClaimOwnershipContext claimContext = ClaimOwnershipContext.get();
        if (claimContext != null && claimContext.isOwnershipLost()) {
            log.warn("Claim ownership lost prior to playlist creation call for key {}", claimContext.idempotencyKey());
            throw new StaleClaimOwnershipException(
                    claimContext.idempotencyKey(),
                    claimContext.claimOwner(),
                    claimContext.claimGeneration()
            );
        }

        String destinationPlaylistId;
        String destinationPlaylistUrl;
        try {
            log.info("Creating destination playlist on YouTube with marker opId {} for review session {}",
                    opId, request.reviewSessionId());
            DestinationPlaylistCreationResult playlistResult = playlistCreationService.createDestinationPlaylist(markedRequest, authenticatedUserId);
            destinationPlaylistId = playlistResult.destinationPlaylistId();
            destinationPlaylistUrl = playlistResult.destinationPlaylistUrl();
            log.info("Destination playlist created successfully: {}", destinationPlaylistId);
        } catch (Exception ex) {
            if (isAmbiguousPlaylistCreationOutcome(ex) && playlistReconciliationService != null) {
                log.warn("Destination playlist creation failed with ambiguous outcome {}. Initiating external reconciliation for session {} with opId {}",
                        ex.getMessage(), request.reviewSessionId(), opId);
                PlaylistReconciliationResult recon = playlistReconciliationService.reconcilePlaylistCreation(
                        authenticatedUserId, opId, request.getTargetPlaylistTitle().orElse(null)
                );
                if (recon.isExactMatch()) {
                    DestinationPlaylist p = recon.matchedPlaylist();
                    log.info("Reconciliation succeeded: found exact matching destination playlist {}", p.id());
                    destinationPlaylistId = p.id();
                    destinationPlaylistUrl = p.url();
                } else {
                    executionStateService.recordPlaylistCreationFailure(
                            executionId, recon.outcome().name(), MigrationExecutionStatus.RECOVERY_REQUIRED, authenticatedUserId
                    );
                    throw new InvalidStateException("Playlist creation failed and reconciliation yielded: " + recon.outcome()
                            + " (" + recon.diagnosticMessage() + ")", ex);
                }
            } else {
                if (ex instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(ex);
            }
        }

        executionStateService.confirmDestinationPlaylist(
                executionId, destinationPlaylistId, destinationPlaylistUrl, nextStatus, authenticatedUserId
        );
        if (idempotencyService != null) {
            idempotencyService.recordDestinationPlaylistForSession(request.reviewSessionId(), destinationPlaylistId);
        }

        return new DestinationPlaylistDetails(destinationPlaylistId, destinationPlaylistUrl);
    }

    private MigrationExecutionSummary resolveAllocatingDestinationExecution(
            MigrationExecutionSummary existing,
            ApprovedMigrationExecutionRequest request,
            UUID authenticatedUserId,
            MigrationExecutionStatus nextStatus
    ) {
        UUID opId = existing.playlistCreationOperationId();
        if (opId == null) {
            throw new InvalidStateException("Execution " + existing.executionId() + " in ALLOCATING_DESTINATION is missing operation ID");
        }

        if (playlistReconciliationService != null) {
            PlaylistReconciliationResult recon = playlistReconciliationService.reconcilePlaylistCreation(
                    authenticatedUserId, opId, request.getTargetPlaylistTitle().orElse(null)
            );
            if (recon.isExactMatch()) {
                DestinationPlaylist p = recon.matchedPlaylist();
                log.info("Reconciled existing ALLOCATING_DESTINATION execution {}: found playlist {}",
                        existing.executionId(), p.id());
                MigrationExecutionSummary confirmed = executionStateService.confirmDestinationPlaylist(
                        existing.executionId(), p.id(), p.url(), nextStatus, authenticatedUserId
                );
                if (idempotencyService != null) {
                    idempotencyService.recordDestinationPlaylistForSession(request.reviewSessionId(), p.id());
                }
                return confirmed;
            } else if (recon.outcome() == PlaylistReconciliationOutcome.CONCLUSIVELY_ABSENT) {
                log.info("Reconciliation confirmed playlist is CONCLUSIVELY_ABSENT for opId {}. Attempting creation retry.", opId);
                createOrReconcileDestinationPlaylist(
                        existing.executionId(), opId, request, authenticatedUserId, nextStatus
                );
                return executionStateService.getExecution(existing.executionId(), authenticatedUserId);
            } else {
                executionStateService.recordPlaylistCreationFailure(
                        existing.executionId(), recon.outcome().name(), MigrationExecutionStatus.RECOVERY_REQUIRED, authenticatedUserId
                );
                throw new InvalidStateException("Playlist creation reconciliation failed for existing execution: " + recon.outcome()
                        + " (" + recon.diagnosticMessage() + ")");
            }
        }

        throw new InvalidStateException("Cannot reconcile ALLOCATING_DESTINATION execution without reconciliation service");
    }

    boolean isAmbiguousPlaylistCreationOutcome(Exception ex) {
        if (ex == null) {
            return false;
        }
        if (ex instanceof IllegalArgumentException
                || ex instanceof NullPointerException
                || ex instanceof IllegalStateException
                || ex instanceof MigrationExecutionValidationException) {
            return false;
        }

        ProviderFailureClassification classification = providerFailureClassifier.classify(ex);
        return classification.category() == ProviderFailureCategory.TRANSIENT_SERVER_ERROR;
    }

    private record DestinationPlaylistDetails(String playlistId, String playlistUrl) {}

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
