package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItem;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionRollbackSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionTrackRecordDto;
import com.spotifyyoutube.migrator.migration.application.execution.contract.InitializeExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.InsertTracksExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RecoverExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackExecutionStatus;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackRecordDto;
import com.spotifyyoutube.migrator.migration.application.execution.contract.RollbackTrackOutcomeDto;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionRollbackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionRollbackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistInspection;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItemDetails;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionReconciliationStatusDto;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionReconciliationSummary;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ReconcileExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ReconciliationRecordDto;
import com.spotifyyoutube.migrator.migration.application.execution.contract.TrackReconciliationOutcomeDto;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionReconciliationRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ReconciliationOutcome;
import com.spotifyyoutube.migrator.migration.execution.domain.ReconciliationType;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionReconciliationRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import com.spotifyyoutube.migrator.migration.application.execution.failure.ExecutionFailureAnalysis;
import com.spotifyyoutube.migrator.migration.application.execution.failure.ExecutionFailureClassification;
import com.spotifyyoutube.migrator.migration.application.execution.failure.ExecutionFailureClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Default implementation of {@link MigrationExecutionStateService}.
 * <p>
 * Enforces discrete, short database transactions around remote mutations, concurrency claims,
 * optimistic locking, crash state reconciliation, safe resume, controlled recovery, and partial rollback.
 */
@Service
public class DefaultMigrationExecutionStateService implements MigrationExecutionStateService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMigrationExecutionStateService.class);

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)(bearer\\s+|(?:access_token|refresh_token|token|api_key|apikey|key|client_secret|secret|password)=)[^\\s&\"',;]+"
    );

    private final MigrationExecutionRepository executionRepository;
    private final ExecutionTrackRecordRepository trackRecordRepository;
    private final ExecutionRollbackRecordRepository rollbackRecordRepository;
    private final ExecutionReconciliationRecordRepository reconciliationRecordRepository;
    private final ReviewSessionRepository reviewSessionRepository;
    private final ApprovedMigrationExecutionService executionService;
    private final MusicDestination musicDestination;
    private final ExecutionFailureClassifier failureClassifier;
    private final TransactionTemplate transactionTemplate;

    public DefaultMigrationExecutionStateService(
            MigrationExecutionRepository executionRepository,
            ExecutionTrackRecordRepository trackRecordRepository,
            ExecutionRollbackRecordRepository rollbackRecordRepository,
            ExecutionReconciliationRecordRepository reconciliationRecordRepository,
            ReviewSessionRepository reviewSessionRepository,
            ApprovedMigrationExecutionService executionService,
            MusicDestination musicDestination,
            ExecutionFailureClassifier failureClassifier,
            TransactionTemplate transactionTemplate
    ) {
        this.executionRepository = Objects.requireNonNull(executionRepository, "MigrationExecutionRepository must not be null");
        this.trackRecordRepository = Objects.requireNonNull(trackRecordRepository, "ExecutionTrackRecordRepository must not be null");
        this.rollbackRecordRepository = rollbackRecordRepository;
        this.reconciliationRecordRepository = reconciliationRecordRepository;
        this.reviewSessionRepository = Objects.requireNonNull(reviewSessionRepository, "ReviewSessionRepository must not be null");
        this.executionService = Objects.requireNonNull(executionService, "ApprovedMigrationExecutionService must not be null");
        this.musicDestination = Objects.requireNonNull(musicDestination, "MusicDestination must not be null");
        this.failureClassifier = failureClassifier != null ? failureClassifier : new ExecutionFailureClassifier();
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "TransactionTemplate must not be null");
    }

    public DefaultMigrationExecutionStateService(
            MigrationExecutionRepository executionRepository,
            ExecutionTrackRecordRepository trackRecordRepository,
            ExecutionRollbackRecordRepository rollbackRecordRepository,
            ReviewSessionRepository reviewSessionRepository,
            ApprovedMigrationExecutionService executionService,
            MusicDestination musicDestination,
            ExecutionFailureClassifier failureClassifier,
            TransactionTemplate transactionTemplate
    ) {
        this(executionRepository, trackRecordRepository, rollbackRecordRepository, null, reviewSessionRepository, executionService, musicDestination, failureClassifier, transactionTemplate);
    }

    public DefaultMigrationExecutionStateService(
            MigrationExecutionRepository executionRepository,
            ExecutionTrackRecordRepository trackRecordRepository,
            ExecutionRollbackRecordRepository rollbackRecordRepository,
            ReviewSessionRepository reviewSessionRepository,
            ApprovedMigrationExecutionService executionService,
            MusicDestination musicDestination,
            TransactionTemplate transactionTemplate
    ) {
        this(executionRepository, trackRecordRepository, rollbackRecordRepository, null, reviewSessionRepository, executionService, musicDestination, new ExecutionFailureClassifier(), transactionTemplate);
    }

    public DefaultMigrationExecutionStateService(
            MigrationExecutionRepository executionRepository,
            ExecutionTrackRecordRepository trackRecordRepository,
            ReviewSessionRepository reviewSessionRepository,
            ApprovedMigrationExecutionService executionService,
            MusicDestination musicDestination,
            TransactionTemplate transactionTemplate
    ) {
        this(executionRepository, trackRecordRepository, null, null, reviewSessionRepository, executionService, musicDestination, new ExecutionFailureClassifier(), transactionTemplate);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultMigrationExecutionStateService(
            MigrationExecutionRepository executionRepository,
            ExecutionTrackRecordRepository trackRecordRepository,
            ExecutionRollbackRecordRepository rollbackRecordRepository,
            ExecutionReconciliationRecordRepository reconciliationRecordRepository,
            ReviewSessionRepository reviewSessionRepository,
            ApprovedMigrationExecutionService executionService,
            MusicDestination musicDestination,
            ExecutionFailureClassifier failureClassifier,
            PlatformTransactionManager transactionManager
    ) {
        this(
                executionRepository,
                trackRecordRepository,
                rollbackRecordRepository,
                reconciliationRecordRepository,
                reviewSessionRepository,
                executionService,
                musicDestination,
                failureClassifier,
                new TransactionTemplate(Objects.requireNonNull(transactionManager, "PlatformTransactionManager must not be null"))
        );
    }

    public DefaultMigrationExecutionStateService(
            MigrationExecutionRepository executionRepository,
            ExecutionTrackRecordRepository trackRecordRepository,
            ExecutionRollbackRecordRepository rollbackRecordRepository,
            ReviewSessionRepository reviewSessionRepository,
            ApprovedMigrationExecutionService executionService,
            MusicDestination musicDestination,
            ExecutionFailureClassifier failureClassifier,
            PlatformTransactionManager transactionManager
    ) {
        this(
                executionRepository,
                trackRecordRepository,
                rollbackRecordRepository,
                null,
                reviewSessionRepository,
                executionService,
                musicDestination,
                failureClassifier,
                new TransactionTemplate(Objects.requireNonNull(transactionManager, "PlatformTransactionManager must not be null"))
        );
    }

    public DefaultMigrationExecutionStateService(
            MigrationExecutionRepository executionRepository,
            ExecutionTrackRecordRepository trackRecordRepository,
            ReviewSessionRepository reviewSessionRepository,
            ApprovedMigrationExecutionService executionService,
            MusicDestination musicDestination,
            PlatformTransactionManager transactionManager
    ) {
        this(
                executionRepository,
                trackRecordRepository,
                null,
                null,
                reviewSessionRepository,
                executionService,
                musicDestination,
                new ExecutionFailureClassifier(),
                new TransactionTemplate(Objects.requireNonNull(transactionManager, "PlatformTransactionManager must not be null"))
        );
    }

    @Override
    public MigrationExecutionSummary initializeExecution(InitializeExecutionRequest request, UUID authenticatedUserId) {
        if (request == null) {
            throw new IllegalArgumentException("InitializeExecutionRequest must not be null");
        }
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user ID must not be null");
        }

        // Anti-impersonation: caller-supplied userId must match authenticated principal
        if (request.getUserId().isPresent()) {
            UUID requestedUserId = request.getUserId().get();
            if (!Objects.equals(requestedUserId, authenticatedUserId)) {
                throw new IllegalArgumentException(String.format(
                        "Requested user ID %s does not match authenticated user %s",
                        requestedUserId, authenticatedUserId
                ));
            }
        }

        String playlistId = request.destinationPlaylistId().trim();

        return transactionTemplate.execute(status -> {
            // Check for existing durable execution for this review session
            Optional<MigrationExecution> existingOpt = executionRepository.findByReviewSessionId(request.reviewSessionId());
            if (existingOpt.isPresent()) {
                MigrationExecution existing = existingOpt.get();
                if (!existing.getUser().getId().equals(authenticatedUserId)) {
                    throw new ResourceNotFoundException("Review session not found for authenticated user: " + request.reviewSessionId());
                }
                if (!existing.getDestinationPlaylistId().equals(playlistId)) {
                    throw new InvalidStateException(String.format(
                            "Review session %s is already bound to destination playlist %s, cannot reinitialize with %s",
                            request.reviewSessionId(), existing.getDestinationPlaylistId(), playlistId
                    ));
                }
                log.info("Found existing durable execution {} for review session {}", existing.getId(), request.reviewSessionId());
                return MigrationExecutionSummary.from(existing);
            }

            // Gated validation of approved migration context
            ApprovedMigrationContext context = executionService.prepareExecution(request.reviewSessionId(), authenticatedUserId);

            ReviewSession session = reviewSessionRepository.findById(request.reviewSessionId())
                    .orElseThrow(() -> new ResourceNotFoundException("Review session not found: " + request.reviewSessionId()));

            if (!session.getUser().getId().equals(authenticatedUserId)) {
                throw new ResourceNotFoundException("Review session not found for authenticated user: " + request.reviewSessionId());
            }

            // Verify playlist existence and accessibility under user credentials
            try {
                musicDestination.getPlaylist(authenticatedUserId, playlistId);
            } catch (ResourceNotFoundException ex) {
                log.warn("Destination playlist {} not found or inaccessible for user {}", playlistId, authenticatedUserId);
                throw new ResourceNotFoundException("Destination playlist not found or inaccessible: " + playlistId);
            }

            User user = session.getUser();
            String playlistUrl = request.destinationPlaylistUrl();

            MigrationExecution execution = new MigrationExecution(user, session, context, playlistId, playlistUrl);
            MigrationExecution saved = executionRepository.saveAndFlush(execution);

            log.info("Initialized durable execution {} for review session {} with {} approved tracks",
                    saved.getId(), request.reviewSessionId(), saved.getTotalApprovedTracks());

            return MigrationExecutionSummary.from(saved);
        });
    }

    @Override
    public MigrationExecutionSummary getExecution(UUID executionId, UUID authenticatedUserId) {
        if (executionId == null) {
            throw new IllegalArgumentException("Execution ID must not be null");
        }
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user ID must not be null");
        }

        return transactionTemplate.execute(status -> {
            MigrationExecution execution = executionRepository.findById(executionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Migration execution not found: " + executionId));

            if (!execution.getUser().getId().equals(authenticatedUserId)) {
                throw new ResourceNotFoundException("Migration execution not found: " + executionId);
            }

            // Reconcile stale crash state if needed
            if (execution.getStatus() == MigrationExecutionStatus.RUNNING) {
                execution.reconcileStaleCrashState();
                execution = executionRepository.saveAndFlush(execution);
            }

            return MigrationExecutionSummary.from(execution);
        });
    }

    @Override
    public MigrationExecutionSummary getExecutionByReviewSession(UUID reviewSessionId, UUID authenticatedUserId) {
        if (reviewSessionId == null) {
            throw new IllegalArgumentException("Review session ID must not be null");
        }
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user ID must not be null");
        }

        return transactionTemplate.execute(status -> {
            MigrationExecution execution = executionRepository.findByReviewSessionId(reviewSessionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Migration execution not found for review session: " + reviewSessionId));

            if (!execution.getUser().getId().equals(authenticatedUserId)) {
                throw new ResourceNotFoundException("Migration execution not found for review session: " + reviewSessionId);
            }

            if (execution.getStatus() == MigrationExecutionStatus.RUNNING) {
                execution.reconcileStaleCrashState();
                execution = executionRepository.saveAndFlush(execution);
            }

            return MigrationExecutionSummary.from(execution);
        });
    }

    @Override
    public MigrationExecutionSummary executeTrackInsertion(UUID executionId, UUID authenticatedUserId) {
        return processInsertion(executionId, authenticatedUserId, false);
    }

    @Override
    public MigrationExecutionSummary executeTrackInsertion(
            InsertTracksExecutionRequest request,
            UUID authenticatedUserId
    ) {
        if (request == null) {
            throw new IllegalArgumentException("InsertTracksExecutionRequest must not be null");
        }
        InitializeExecutionRequest initReq = InitializeExecutionRequest.of(
                request.reviewSessionId(),
                request.destinationPlaylistId(),
                authenticatedUserId
        );
        MigrationExecutionSummary initSummary = initializeExecution(initReq, authenticatedUserId);
        return executeTrackInsertion(initSummary.executionId(), authenticatedUserId);
    }

    @Override
    public MigrationExecutionSummary resumeExecution(UUID executionId, UUID authenticatedUserId) {
        return processInsertion(executionId, authenticatedUserId, true);
    }

    private MigrationExecutionSummary processInsertion(UUID executionId, UUID authenticatedUserId, boolean isResume) {
        if (executionId == null) {
            throw new IllegalArgumentException("Execution ID must not be null");
        }
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user ID must not be null");
        }

        // 1. Claim execution in a short database transaction
        String playlistId = transactionTemplate.execute(status -> {
            MigrationExecution execution = executionRepository.findById(executionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Migration execution not found: " + executionId));

            if (!execution.getUser().getId().equals(authenticatedUserId)) {
                throw new ResourceNotFoundException("Migration execution not found: " + executionId);
            }

            if (execution.getStatus() == MigrationExecutionStatus.RUNNING) {
                throw new InvalidStateException("Migration execution is already in progress by another request");
            }

            if (execution.isTerminal()) {
                throw new InvalidStateException("Cannot execute or resume: execution is in terminal status " + execution.getStatus());
            }

            // Stale crash reconciliation: if previously crashed during execution with ADDING tracks
            execution.reconcileStaleCrashState();

            if (isResume && !execution.canResume()) {
                throw new InvalidStateException("Execution cannot be resumed in current status: " + execution.getStatus());
            }

            // Claim execution lock with atomic conditional update
            Set<MigrationExecutionStatus> eligibleStatuses = Set.of(
                    MigrationExecutionStatus.INITIALIZED,
                    MigrationExecutionStatus.PARTIALLY_COMPLETED,
                    MigrationExecutionStatus.PARTIALLY_ROLLED_BACK,
                    MigrationExecutionStatus.RECOVERY_REQUIRED
            );
            long expectedVersion = execution.getVersion() != null ? execution.getVersion() : 0L;
            int claimed = executionRepository.claimExecutionRunningWithVersion(
                    executionId,
                    eligibleStatuses,
                    expectedVersion,
                    Instant.now()
            );
            if (claimed == 0) {
                throw new InvalidStateException("Migration execution could not be claimed: " +
                        "execution is already in progress, in an invalid state, or was modified concurrently");
            }
            execution.claimExecution();

            return execution.getDestinationPlaylistId();
        });

        // 2. Perform sequential insertion using short per-track transactions
        return executePendingTracks(executionId, authenticatedUserId, playlistId);
    }

    @Override
    public MigrationExecutionSummary recoverExecution(UUID executionId, UUID authenticatedUserId) {
        return recoverExecution(executionId, RecoverExecutionRequest.all(), authenticatedUserId);
    }

    @Override
    public MigrationExecutionSummary recoverExecution(UUID executionId, RecoverExecutionRequest request, UUID authenticatedUserId) {
        if (executionId == null) {
            throw new IllegalArgumentException("Execution ID must not be null");
        }
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user ID must not be null");
        }
        if (request == null) {
            request = RecoverExecutionRequest.all();
        }

        // Anti-impersonation: caller-supplied userId must match authenticated principal
        if (request.getUserId().isPresent()) {
            UUID requestedUserId = request.getUserId().get();
            if (!Objects.equals(requestedUserId, authenticatedUserId)) {
                throw new IllegalArgumentException(String.format(
                        "Requested user ID %s does not match authenticated user %s",
                        requestedUserId, authenticatedUserId
                ));
            }
        }

        final RecoverExecutionRequest recoveryReq = request;

        // 1. Validate state and claim execution in a short transaction
        String playlistId = transactionTemplate.execute(status -> {
            MigrationExecution execution = executionRepository.findById(executionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Migration execution not found: " + executionId));

            if (!execution.getUser().getId().equals(authenticatedUserId)) {
                throw new ResourceNotFoundException("Migration execution not found: " + executionId);
            }

            if (execution.getStatus() == MigrationExecutionStatus.RUNNING) {
                throw new InvalidStateException("Migration execution is already in progress by another request");
            }

            if (execution.getStatus() == MigrationExecutionStatus.COMPLETED) {
                throw new InvalidStateException("Cannot recover execution: execution is already in terminal status COMPLETED");
            }

            if (execution.getStatus() == MigrationExecutionStatus.ROLLED_BACK) {
                throw new InvalidStateException("Cannot recover execution: execution has been rolled back");
            }

            // Reconcile stale crash state if needed
            execution.reconcileStaleCrashState();

            // Invariant: Reject recovery if there are UNCERTAIN tracks
            if (execution.getUncertainInsertions() > 0 || execution.getTrackRecords().stream().anyMatch(t -> t.getStatus() == ExecutionTrackStatus.UNCERTAIN)) {
                throw new InvalidStateException("Cannot recover execution: execution contains uncertain tracks that require manual reconciliation before recovery can proceed");
            }

            // Identify eligible failed tracks
            List<ExecutionTrackRecord> records = trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId);

            if (recoveryReq.getTargetPosition().isPresent()) {
                int targetPos = recoveryReq.getTargetPosition().get();
                ExecutionTrackRecord targetRecord = records.stream()
                        .filter(r -> r.getTargetPosition() == targetPos)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Target position " + targetPos + " does not exist in execution"));

                if (targetRecord.getStatus() != ExecutionTrackStatus.FAILED) {
                    throw new InvalidStateException(String.format(
                            "Cannot recover track at position %d: track is in status %s, not FAILED",
                            targetPos, targetRecord.getStatus()
                    ));
                }

                // Check that no prior track is FAILED or UNCERTAIN (canonical ordering invariant)
                boolean priorFailed = records.stream()
                        .filter(r -> r.getTargetPosition() < targetPos)
                        .anyMatch(r -> r.getStatus() == ExecutionTrackStatus.FAILED || r.getStatus() == ExecutionTrackStatus.UNCERTAIN);
                if (priorFailed) {
                    throw new InvalidStateException("Cannot recover track at position " + targetPos + ": a prior track is failed or uncertain");
                }

                // Reset this track to PENDING
                targetRecord.resetForRecovery();
                trackRecordRepository.save(targetRecord);

                // Reset any subsequent SKIPPED tracks to PENDING so they can be processed in canonical order
                records.stream()
                        .filter(r -> r.getTargetPosition() > targetPos && r.getStatus() == ExecutionTrackStatus.SKIPPED)
                        .forEach(r -> {
                            r.resetForRecovery();
                            trackRecordRepository.save(r);
                        });
            } else {
                List<ExecutionTrackRecord> failedTracks = records.stream()
                        .filter(r -> r.getStatus() == ExecutionTrackStatus.FAILED)
                        .toList();

                if (failedTracks.isEmpty()) {
                    throw new InvalidStateException("No eligible failed tracks to recover in execution: " + executionId);
                }

                int firstFailedPos = failedTracks.get(0).getTargetPosition();

                // Reset all failed tracks and subsequent skipped tracks to PENDING
                records.stream()
                        .filter(r -> r.getTargetPosition() >= firstFailedPos && (r.getStatus() == ExecutionTrackStatus.FAILED || r.getStatus() == ExecutionTrackStatus.SKIPPED))
                        .forEach(r -> {
                            r.resetForRecovery();
                            trackRecordRepository.save(r);
                        });
            }

            trackRecordRepository.flush();

            // Claim execution lock for recovery with atomic conditional update
            Set<MigrationExecutionStatus> eligibleStatuses = Set.of(
                    MigrationExecutionStatus.PARTIALLY_COMPLETED,
                    MigrationExecutionStatus.FAILED,
                    MigrationExecutionStatus.RECOVERY_REQUIRED,
                    MigrationExecutionStatus.PARTIALLY_ROLLED_BACK
            );
            long expectedVersion = execution.getVersion() != null ? execution.getVersion() : 0L;
            int claimed = executionRepository.claimExecutionRunningWithVersion(
                    executionId,
                    eligibleStatuses,
                    expectedVersion,
                    Instant.now()
            );
            if (claimed == 0) {
                throw new InvalidStateException("Migration execution could not be claimed for recovery: " +
                        "execution is either already in progress, in an invalid state, or was modified concurrently");
            }
            execution.claimExecution();

            return execution.getDestinationPlaylistId();
        });

        // 2. Perform sequential insertion using the established short-transaction pattern
        return executePendingTracks(executionId, authenticatedUserId, playlistId);
    }

    private MigrationExecutionSummary executePendingTracks(UUID executionId, UUID authenticatedUserId, String playlistId) {
        // Load track records ordered by canonical target position
        List<ExecutionTrackRecordDto> allTracks = transactionTemplate.execute(status -> {
            List<ExecutionTrackRecord> records = trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId);
            return records.stream().map(ExecutionTrackRecordDto::from).toList();
        });

        // Calculate continuous destination insertion index based on already ADDED tracks
        int currentDestinationIndex = (int) allTracks.stream()
                .filter(t -> t.status() == ExecutionTrackStatus.ADDED)
                .count();

        List<ExecutionTrackRecordDto> eligiblePending = allTracks.stream()
                .filter(t -> t.status() == ExecutionTrackStatus.PENDING)
                .sorted(Comparator.comparingInt(ExecutionTrackRecordDto::targetPosition))
                .toList();

        boolean stopRemaining = false;
        String stopReason = null;

        // Sequential Insertion with Short Per-Track Transactions
        for (ExecutionTrackRecordDto track : eligiblePending) {
            UUID trackId = track.id();

            // a) Mark track as ADDING in a short transaction before remote call
            transactionTemplate.execute(status -> {
                ExecutionTrackRecord record = trackRecordRepository.findById(trackId)
                        .orElseThrow(() -> new IllegalStateException("Track record missing: " + trackId));
                record.markAdding();
                trackRecordRepository.saveAndFlush(record);
                return null;
            });

            // b) Invoke external destination provider (OUTSIDE database transaction)
            try {
                AddTrackToDestinationPlaylistRequest addRequest = new AddTrackToDestinationPlaylistRequest(
                        track.destinationTrackId(),
                        currentDestinationIndex
                );

                DestinationPlaylistItem item = musicDestination.addTrackToPlaylist(
                        authenticatedUserId,
                        playlistId,
                        addRequest
                );

                if (item != null && item.id() != null && !item.id().isBlank()) {
                    // c) Mark ADDED in a short transaction
                    transactionTemplate.execute(status -> {
                        ExecutionTrackRecord record = trackRecordRepository.findById(trackId)
                                .orElseThrow(() -> new IllegalStateException("Track record missing: " + trackId));
                        record.markAdded(item.id().trim());
                        trackRecordRepository.saveAndFlush(record);
                        return null;
                    });
                    currentDestinationIndex++;
                } else {
                    transactionTemplate.execute(status -> {
                        ExecutionTrackRecord record = trackRecordRepository.findById(trackId)
                                .orElseThrow(() -> new IllegalStateException("Track record missing: " + trackId));
                        record.markFailed("Provider returned null or missing playlist item ID");
                        trackRecordRepository.saveAndFlush(record);
                        return null;
                    });
                    stopRemaining = true;
                    stopReason = "failure";
                    break;
                }
            } catch (ResourceAccessException ex) {
                // Ambiguous failure: network timeout / connection reset
                ExecutionFailureAnalysis analysis = failureClassifier.classify(ex, "destination_insertion");
                String sanitizedMsg = sanitize(ex.getMessage());
                log.warn("Insertion timed out for track {} in execution {}. Remote outcome is UNCERTAIN: {}",
                        track.destinationTrackId(), executionId, sanitizedMsg);

                transactionTemplate.execute(status -> {
                    ExecutionTrackRecord record = trackRecordRepository.findById(trackId)
                            .orElseThrow(() -> new IllegalStateException("Track record missing: " + trackId));
                    record.markUncertain("Insertion timed out; remote outcome is uncertain: " + sanitizedMsg);
                    trackRecordRepository.saveAndFlush(record);
                    return null;
                });
                stopRemaining = true;
                stopReason = "timeout (uncertain outcome)";
                break;
            } catch (ExternalProviderException ex) {
                ExecutionFailureAnalysis analysis = failureClassifier.classify(ex, "destination_insertion");
                String sanitizedMsg = sanitize(ex.getMessage());
                log.error("Destination provider failed for track {} in execution {}: classification={}, msg={}",
                        track.destinationTrackId(), executionId, analysis.classification(), sanitizedMsg);

                if (analysis.classification() == ExecutionFailureClassification.MANUAL_INTERVENTION_REQUIRED) {
                    // Ambiguous outcome (e.g. 500, 503 during remote mutation)
                    transactionTemplate.execute(status -> {
                        ExecutionTrackRecord record = trackRecordRepository.findById(trackId)
                                .orElseThrow(() -> new IllegalStateException("Track record missing: " + trackId));
                        record.markUncertain(sanitizedMsg);
                        trackRecordRepository.saveAndFlush(record);
                        return null;
                    });
                    stopRemaining = true;
                    stopReason = "uncertain outcome (" + analysis.classification() + ")";
                } else {
                    // Confirmed provider rejection (e.g. 400, 401, 403, 404)
                    transactionTemplate.execute(status -> {
                        ExecutionTrackRecord record = trackRecordRepository.findById(trackId)
                                .orElseThrow(() -> new IllegalStateException("Track record missing: " + trackId));
                        record.markFailed(sanitizedMsg);
                        trackRecordRepository.saveAndFlush(record);
                        return null;
                    });
                    stopRemaining = true;
                    stopReason = "provider rejection";
                }
                break;
            } catch (Exception ex) {
                ExecutionFailureAnalysis analysis = failureClassifier.classify(ex, "destination_insertion");
                String sanitizedMsg = sanitize(ex.getMessage());
                log.error("Unexpected error inserting track {} in execution {}: {}",
                        track.destinationTrackId(), executionId, sanitizedMsg);

                if (analysis.classification() == ExecutionFailureClassification.MANUAL_INTERVENTION_REQUIRED) {
                    transactionTemplate.execute(status -> {
                        ExecutionTrackRecord record = trackRecordRepository.findById(trackId)
                                .orElseThrow(() -> new IllegalStateException("Track record missing: " + trackId));
                        record.markUncertain("Unexpected error with uncertain outcome: " + sanitizedMsg);
                        trackRecordRepository.saveAndFlush(record);
                        return null;
                    });
                    stopRemaining = true;
                    stopReason = "unexpected error (uncertain outcome)";
                } else {
                    transactionTemplate.execute(status -> {
                        ExecutionTrackRecord record = trackRecordRepository.findById(trackId)
                                .orElseThrow(() -> new IllegalStateException("Track record missing: " + trackId));
                        record.markFailed("Unexpected insertion error: " + sanitizedMsg);
                        trackRecordRepository.saveAndFlush(record);
                        return null;
                    });
                    stopRemaining = true;
                    stopReason = "unexpected error";
                }
                break;
            }
        }

        // If aborted, mark remaining PENDING tracks as SKIPPED
        if (stopRemaining) {
            String skipReason = "Skipped due to prior insertion " + stopReason;
            transactionTemplate.execute(status -> {
                List<ExecutionTrackRecord> remaining = trackRecordRepository.findByExecutionIdAndStatus(
                        executionId,
                        ExecutionTrackStatus.PENDING
                );
                for (ExecutionTrackRecord rec : remaining) {
                    rec.markSkipped(skipReason);
                    trackRecordRepository.save(rec);
                }
                trackRecordRepository.flush();
                return null;
            });
        }

        // Release execution claim, reconcile counts, and finalize status
        return transactionTemplate.execute(status -> {
            MigrationExecution execution = executionRepository.findById(executionId)
                    .orElseThrow(() -> new IllegalStateException("Migration execution missing: " + executionId));

            execution.releaseExecution();
            MigrationExecution saved = executionRepository.saveAndFlush(execution);
            if (saved == null) {
                saved = execution;
            }

            log.info("Execution {} finalized with status {}: {} added, {} failed, {} uncertain, {} skipped, {} rolled back out of {} total",
                    saved.getId(), saved.getStatus(), saved.getSuccessfulInsertions(),
                    saved.getFailedInsertions(), saved.getUncertainInsertions(),
                    saved.getSkippedInsertions(), saved.getRolledBackInsertions(), saved.getTotalApprovedTracks());

            return MigrationExecutionSummary.from(saved);
        });
    }

    @Override
    public ExecutionRollbackSummary rollbackExecution(UUID executionId, UUID authenticatedUserId) {
        return rollbackExecution(executionId, RollbackExecutionRequest.all(), authenticatedUserId);
    }

    @Override
    public ExecutionRollbackSummary rollbackExecution(UUID executionId, RollbackExecutionRequest request, UUID authenticatedUserId) {
        if (executionId == null) {
            throw new IllegalArgumentException("Execution ID must not be null");
        }
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user ID must not be null");
        }
        if (request == null) {
            request = RollbackExecutionRequest.all();
        }

        // Anti-impersonation
        if (request.getUserId().isPresent()) {
            UUID requestedUserId = request.getUserId().get();
            if (!Objects.equals(requestedUserId, authenticatedUserId)) {
                throw new IllegalArgumentException(String.format(
                        "Requested user ID %s does not match authenticated user %s",
                        requestedUserId, authenticatedUserId
                ));
            }
        }

        final RollbackExecutionRequest rollbackReq = request;

        // 1. Concurrency claim and validation in a short transaction
        List<ExecutionTrackRecordDto> targetsToRollback = transactionTemplate.execute(status -> {
            MigrationExecution execution = executionRepository.findById(executionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Migration execution not found: " + executionId));

            if (!execution.getUser().getId().equals(authenticatedUserId)) {
                throw new ResourceNotFoundException("Migration execution not found: " + executionId);
            }

            if (execution.getStatus() == MigrationExecutionStatus.RUNNING) {
                throw new InvalidStateException("Migration execution is currently running; cannot rollback during execution");
            }

            List<ExecutionTrackRecord> allRecords = trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId);

            List<ExecutionTrackRecord> eligible;
            if (rollbackReq.targetPositions() != null && !rollbackReq.targetPositions().isEmpty()) {
                // Validate requested positions exist
                for (int pos : rollbackReq.targetPositions()) {
                    boolean exists = allRecords.stream().anyMatch(r -> r.getTargetPosition() == pos);
                    if (!exists) {
                        throw new IllegalArgumentException("Target position " + pos + " does not exist in execution");
                    }
                }

                eligible = allRecords.stream()
                        .filter(r -> rollbackReq.targetPositions().contains(r.getTargetPosition()))
                        .toList();

                for (ExecutionTrackRecord r : eligible) {
                    if (r.getStatus() == ExecutionTrackStatus.PENDING
                            || r.getStatus() == ExecutionTrackStatus.SKIPPED
                            || r.getStatus() == ExecutionTrackStatus.FAILED) {
                        throw new InvalidStateException(String.format(
                                "Cannot rollback track at position %d: track was never inserted (status: %s)",
                                r.getTargetPosition(), r.getStatus()
                        ));
                    }
                    if (r.getStatus() == ExecutionTrackStatus.UNCERTAIN) {
                        throw new InvalidStateException(String.format(
                                "Cannot rollback track at position %d: track outcome is uncertain",
                                r.getTargetPosition()
                        ));
                    }
                    if (r.getStatus() == ExecutionTrackStatus.ROLLING_BACK) {
                        throw new InvalidStateException(String.format(
                                "Cannot rollback track at position %d: rollback is already in progress for this track",
                                r.getTargetPosition()
                        ));
                    }
                }
            } else {
                eligible = allRecords.stream()
                        .filter(r -> r.getStatus() == ExecutionTrackStatus.ADDED || r.getStatus() == ExecutionTrackStatus.ROLLED_BACK)
                        .toList();

                if (eligible.isEmpty()) {
                    throw new InvalidStateException("No successfully inserted tracks available for rollback in execution: " + executionId);
                }
            }

            // Claim execution for rollback with atomic conditional update
            Set<MigrationExecutionStatus> eligibleStatuses = Set.of(
                    MigrationExecutionStatus.PARTIALLY_COMPLETED,
                    MigrationExecutionStatus.COMPLETED,
                    MigrationExecutionStatus.PARTIALLY_ROLLED_BACK,
                    MigrationExecutionStatus.RECOVERY_REQUIRED,
                    MigrationExecutionStatus.FAILED
            );
            long expectedVersion = execution.getVersion() != null ? execution.getVersion() : 0L;
            int claimed = executionRepository.claimExecutionRunningWithVersion(
                    executionId,
                    eligibleStatuses,
                    expectedVersion,
                    Instant.now()
            );
            if (claimed == 0) {
                throw new InvalidStateException("Migration execution could not be claimed for rollback: " +
                        "execution is either already in progress, in an invalid state, or was modified concurrently");
            }
            execution.setStatus(MigrationExecutionStatus.RUNNING);

            return eligible.stream().map(ExecutionTrackRecordDto::from).toList();
        });

        // 2. Perform Rollback Operations
        // Iterate in reverse target position order (compensating operations)
        List<ExecutionTrackRecordDto> sortedTargets = targetsToRollback.stream()
                .sorted(Comparator.comparingInt(ExecutionTrackRecordDto::targetPosition).reversed())
                .toList();

        List<RollbackTrackOutcomeDto> outcomes = new ArrayList<>();
        int successCount = 0;
        int failedCount = 0;
        int alreadyRolledBackCount = 0;

        for (ExecutionTrackRecordDto target : sortedTargets) {
            UUID trackId = target.id();
            int pos = target.targetPosition();
            String videoId = target.destinationTrackId();
            String playlistItemId = target.destinationPlaylistItemId();

            // Idempotency check: if already ROLLED_BACK at application state level, do not call remote provider
            if (target.status() == ExecutionTrackStatus.ROLLED_BACK) {
                alreadyRolledBackCount++;
                outcomes.add(RollbackTrackOutcomeDto.alreadyRolledBack(pos, videoId, playlistItemId));
                if (rollbackRecordRepository != null) {
                    transactionTemplate.execute(status -> {
                        MigrationExecution exec = executionRepository.findById(executionId).orElseThrow();
                        ExecutionTrackRecord tr = trackRecordRepository.findById(trackId).orElseThrow();
                        ExecutionRollbackRecord audit = ExecutionRollbackRecord.alreadyRolledBack(exec, tr);
                        rollbackRecordRepository.save(audit);
                        return null;
                    });
                }
                continue;
            }

            // Verified identifier check: cannot delete without valid playlistItemId
            if (playlistItemId == null || playlistItemId.isBlank()) {
                failedCount++;
                String err = "Cannot rollback track at position " + pos + ": missing or unverified destination playlist item identifier";
                outcomes.add(RollbackTrackOutcomeDto.failed(pos, videoId, playlistItemId, err));
                if (rollbackRecordRepository != null) {
                    transactionTemplate.execute(status -> {
                        MigrationExecution exec = executionRepository.findById(executionId).orElseThrow();
                        ExecutionTrackRecord tr = trackRecordRepository.findById(trackId).orElseThrow();
                        ExecutionRollbackRecord audit = ExecutionRollbackRecord.failed(exec, tr, err);
                        rollbackRecordRepository.save(audit);
                        return null;
                    });
                }
                continue;
            }

            // Atomically claim track in short transaction before calling remote provider
            // to prevent concurrent duplicate remote DELETE calls
            boolean trackClaimed = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                int rows = trackRecordRepository.claimTrackRollingBack(trackId, Instant.now());
                return rows == 1;
            }));

            if (!trackClaimed) {
                failedCount++;
                String conflictMsg = "Cannot rollback track at position " + pos + ": track is concurrently claimed by another operation or not in ADDED status";
                outcomes.add(RollbackTrackOutcomeDto.failed(pos, videoId, playlistItemId, conflictMsg));
                continue;
            }

            // Execute compensating provider operation (OUTSIDE database transaction)
            try {
                musicDestination.removeTrackFromPlaylist(authenticatedUserId, playlistItemId.trim());

                // Confirmed deletion by destination provider -> persist outcome in short transaction
                transactionTemplate.execute(status -> {
                    MigrationExecution exec = executionRepository.findById(executionId).orElseThrow();
                    ExecutionTrackRecord record = trackRecordRepository.findById(trackId).orElseThrow();
                    record.markRolledBack("Rolled back: removed from destination playlist");
                    trackRecordRepository.saveAndFlush(record);

                    if (rollbackRecordRepository != null) {
                        ExecutionRollbackRecord audit = ExecutionRollbackRecord.success(exec, record);
                        rollbackRecordRepository.save(audit);
                    }
                    return null;
                });

                successCount++;
                outcomes.add(RollbackTrackOutcomeDto.rolledBack(pos, videoId, playlistItemId));

            } catch (ResourceNotFoundException ex) {
                // Item was already deleted / absent on destination -> treated as confirmed safe deletion
                transactionTemplate.execute(status -> {
                    MigrationExecution exec = executionRepository.findById(executionId).orElseThrow();
                    ExecutionTrackRecord record = trackRecordRepository.findById(trackId).orElseThrow();
                    record.markRolledBack("Item was already removed on destination provider (404 Not Found)");
                    trackRecordRepository.saveAndFlush(record);

                    if (rollbackRecordRepository != null) {
                        ExecutionRollbackRecord audit = ExecutionRollbackRecord.success(exec, record);
                        rollbackRecordRepository.save(audit);
                    }
                    return null;
                });

                successCount++;
                outcomes.add(RollbackTrackOutcomeDto.rolledBack(pos, videoId, playlistItemId));

            } catch (ResourceAccessException ex) {
                // Ambiguous deletion: network timeout / socket dropped during remote DELETE
                String sanitized = sanitize(ex.getMessage());
                log.warn("Rollback timed out for track {} (item {}). Outcome is UNCERTAIN: {}",
                        videoId, playlistItemId, sanitized);
                failedCount++;
                outcomes.add(RollbackTrackOutcomeDto.failed(pos, videoId, playlistItemId, "UNCERTAIN: " + sanitized));

                transactionTemplate.execute(status -> {
                    MigrationExecution exec = executionRepository.findById(executionId).orElseThrow();
                    ExecutionTrackRecord tr = trackRecordRepository.findById(trackId).orElseThrow();
                    tr.markUncertain("Rollback delete timed out; remote outcome is uncertain: " + sanitized);
                    trackRecordRepository.saveAndFlush(tr);

                    if (rollbackRecordRepository != null) {
                        ExecutionRollbackRecord audit = ExecutionRollbackRecord.uncertain(exec, tr, sanitized);
                        rollbackRecordRepository.save(audit);
                    }
                    return null;
                });

            } catch (ExternalProviderException ex) {
                ExecutionFailureAnalysis analysis = failureClassifier.classify(ex, "destination_deletion");
                String sanitized = sanitize(ex.getMessage());

                if (analysis.classification() == ExecutionFailureClassification.MANUAL_INTERVENTION_REQUIRED) {
                    // Ambiguous deletion: HTTP 500 / 503 during remote DELETE
                    log.warn("Rollback server error HTTP {} for track {} (item {}). Outcome is UNCERTAIN: {}",
                            analysis.getProviderStatusCode().orElse(0), videoId, playlistItemId, sanitized);
                    failedCount++;
                    outcomes.add(RollbackTrackOutcomeDto.failed(pos, videoId, playlistItemId, "UNCERTAIN: " + sanitized));

                    transactionTemplate.execute(status -> {
                        MigrationExecution exec = executionRepository.findById(executionId).orElseThrow();
                        ExecutionTrackRecord tr = trackRecordRepository.findById(trackId).orElseThrow();
                        tr.markUncertain("Rollback outcome uncertain: " + sanitized);
                        trackRecordRepository.saveAndFlush(tr);

                        if (rollbackRecordRepository != null) {
                            ExecutionRollbackRecord audit = ExecutionRollbackRecord.uncertain(exec, tr, sanitized);
                            rollbackRecordRepository.save(audit);
                        }
                        return null;
                    });
                } else {
                    // Confirmed provider rejection (e.g. 400, 401, 403)
                    failedCount++;
                    outcomes.add(RollbackTrackOutcomeDto.failed(pos, videoId, playlistItemId, sanitized));

                    transactionTemplate.execute(status -> {
                        MigrationExecution exec = executionRepository.findById(executionId).orElseThrow();
                        ExecutionTrackRecord tr = trackRecordRepository.findById(trackId).orElseThrow();
                        tr.revertRollingBackToAdded("Rollback rejected by provider: " + sanitized);
                        trackRecordRepository.saveAndFlush(tr);

                        if (rollbackRecordRepository != null) {
                            ExecutionRollbackRecord audit = ExecutionRollbackRecord.failed(exec, tr, sanitized);
                            rollbackRecordRepository.save(audit);
                        }
                        return null;
                    });
                }

            } catch (Exception ex) {
                String sanitized = sanitize(ex.getMessage());
                failedCount++;
                outcomes.add(RollbackTrackOutcomeDto.failed(pos, videoId, playlistItemId, sanitized));

                transactionTemplate.execute(status -> {
                    MigrationExecution exec = executionRepository.findById(executionId).orElseThrow();
                    ExecutionTrackRecord tr = trackRecordRepository.findById(trackId).orElseThrow();
                    tr.revertRollingBackToAdded("Unexpected rollback error: " + sanitized);
                    trackRecordRepository.saveAndFlush(tr);

                    if (rollbackRecordRepository != null) {
                        ExecutionRollbackRecord audit = ExecutionRollbackRecord.failed(exec, tr, sanitized);
                        rollbackRecordRepository.save(audit);
                    }
                    return null;
                });
            }
        }

        // 3. Reconcile aggregate execution state in a short transaction
        MigrationExecutionSummary updatedSummary = transactionTemplate.execute(status -> {
            MigrationExecution execution = executionRepository.findById(executionId).orElseThrow();
            execution.releaseExecution();
            MigrationExecution saved = executionRepository.saveAndFlush(execution);
            return MigrationExecutionSummary.from(saved);
        });

        // Determine overall status
        RollbackExecutionStatus overallStatus;
        if (failedCount == 0 && successCount > 0) {
            overallStatus = RollbackExecutionStatus.SUCCESS;
        } else if (successCount > 0 && failedCount > 0) {
            overallStatus = RollbackExecutionStatus.PARTIAL_SUCCESS;
        } else if (alreadyRolledBackCount > 0 && successCount == 0 && failedCount == 0) {
            overallStatus = RollbackExecutionStatus.NO_OP;
        } else {
            overallStatus = RollbackExecutionStatus.FAILED;
        }

        return new ExecutionRollbackSummary(
                executionId,
                overallStatus,
                sortedTargets.size(),
                successCount,
                failedCount,
                alreadyRolledBackCount,
                outcomes,
                updatedSummary
        );
    }

    @Override
    public List<RollbackRecordDto> getRollbackHistory(UUID executionId, UUID authenticatedUserId) {
        if (executionId == null) {
            throw new IllegalArgumentException("Execution ID must not be null");
        }
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user ID must not be null");
        }

        return transactionTemplate.execute(status -> {
            MigrationExecution execution = executionRepository.findById(executionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Migration execution not found: " + executionId));

            if (!execution.getUser().getId().equals(authenticatedUserId)) {
                throw new ResourceNotFoundException("Migration execution not found: " + executionId);
            }

            if (rollbackRecordRepository == null) {
                return Collections.emptyList();
            }

            return rollbackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId).stream()
                    .map(RollbackRecordDto::from)
                    .toList();
        });
    }

    private String sanitize(String message) {
        if (message == null) {
            return "null";
        }
        return TOKEN_PATTERN.matcher(message).replaceAll("$1[REDACTED]");
    }

    @Override
    public ExecutionReconciliationSummary reconcileExecution(UUID executionId, UUID authenticatedUserId) {
        return reconcileExecution(executionId, ReconcileExecutionRequest.allTracks(authenticatedUserId, null), authenticatedUserId);
    }

    @Override
    public ExecutionReconciliationSummary reconcileExecution(
            UUID executionId,
            ReconcileExecutionRequest request,
            UUID authenticatedUserId
    ) {
        if (executionId == null) {
            throw new IllegalArgumentException("Execution ID must not be null");
        }
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user ID must not be null");
        }
        if (request != null && request.userId() != null && !authenticatedUserId.equals(request.userId())) {
            throw new IllegalArgumentException("Authenticated user does not match the requested user ID");
        }

        // 1. Claim execution and identify target tracks in a short transaction
        record ClaimedReconciliationContext(
                List<ExecutionTrackRecordDto> targets,
                String destinationPlaylistId,
                MigrationExecutionStatus previousExecutionStatus
        ) {}

        ClaimedReconciliationContext claimedContext = transactionTemplate.execute(status -> {
            MigrationExecution execution = executionRepository.findById(executionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Migration execution not found: " + executionId));

            if (!execution.getUser().getId().equals(authenticatedUserId)) {
                throw new ResourceNotFoundException("Migration execution not found: " + executionId);
            }

            MigrationExecutionStatus priorStatus = execution.getStatus();

            // Reconcile stale in-flight state (5 min lease) before checking eligibility
            execution.reconcileStaleCrashState();

            List<ExecutionTrackRecord> allTracks = trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId);
            Instant cutoff = Instant.now().minus(MigrationExecution.DEFAULT_STALE_THRESHOLD);

            List<ExecutionTrackRecord> eligible;
            if (request != null && request.targetPosition() != null) {
                int targetPos = request.targetPosition();
                ExecutionTrackRecord track = allTracks.stream()
                        .filter(t -> t.getTargetPosition() == targetPos)
                        .findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException("Track record not found at position: " + targetPos));

                boolean isEligible = track.getStatus() == ExecutionTrackStatus.UNCERTAIN
                        || (track.getStatus() == ExecutionTrackStatus.ADDING && track.isStaleInFlight(cutoff))
                        || (track.getStatus() == ExecutionTrackStatus.ROLLING_BACK && track.isStaleInFlight(cutoff));

                if (!isEligible) {
                    throw new InvalidStateException(String.format(
                            "Track at position %d is not eligible for reconciliation (current status: %s)",
                            targetPos, track.getStatus()
                    ));
                }
                eligible = List.of(track);
            } else {
                eligible = allTracks.stream()
                        .filter(t -> t.getStatus() == ExecutionTrackStatus.UNCERTAIN
                                || (t.getStatus() == ExecutionTrackStatus.ADDING && t.isStaleInFlight(cutoff))
                                || (t.getStatus() == ExecutionTrackStatus.ROLLING_BACK && t.isStaleInFlight(cutoff)))
                        .toList();

                if (eligible.isEmpty()) {
                    throw new InvalidStateException("No uncertain or stale in-flight tracks available for reconciliation in execution: " + executionId);
                }
            }

            // Execution claiming with optimistic version check
            Set<MigrationExecutionStatus> eligibleStatuses = Set.of(
                    MigrationExecutionStatus.RECOVERY_REQUIRED,
                    MigrationExecutionStatus.PARTIALLY_COMPLETED,
                    MigrationExecutionStatus.FAILED,
                    MigrationExecutionStatus.PARTIALLY_ROLLED_BACK
            );

            long expectedVersion = execution.getVersion() != null ? execution.getVersion() : 0L;
            int claimed = executionRepository.claimExecutionRunningWithVersion(
                    executionId,
                    eligibleStatuses,
                    expectedVersion,
                    Instant.now()
            );

            if (claimed == 0) {
                throw new InvalidStateException("Migration execution could not be claimed for reconciliation: " +
                        "execution is either already in progress, in an invalid state, or was modified concurrently");
            }
            execution.setStatus(MigrationExecutionStatus.RUNNING);

            return new ClaimedReconciliationContext(
                    eligible.stream().map(ExecutionTrackRecordDto::from).toList(),
                    execution.getDestinationPlaylistId(),
                    priorStatus
            );
        });

        // 2. Perform Remote Inspection OUTSIDE database transactions
        Instant inspectionStartedAt = Instant.now();
        DestinationPlaylistInspection inspection;
        try {
            inspection = musicDestination.inspectPlaylist(authenticatedUserId, claimedContext.destinationPlaylistId());
            if (inspection == null) {
                inspection = DestinationPlaylistInspection.failed(
                        claimedContext.destinationPlaylistId(),
                        "Destination provider returned null inspection snapshot",
                        inspectionStartedAt
                );
            }
        } catch (Exception ex) {
            String sanitizedErr = sanitize(ex.getMessage());
            log.error("Destination playlist inspection threw exception for execution {}: {}", executionId, sanitizedErr);
            inspection = DestinationPlaylistInspection.failed(
                    claimedContext.destinationPlaylistId(),
                    sanitizedErr,
                    inspectionStartedAt
            );
        }
        Instant inspectionCompletedAt = Instant.now();

        // 3. Process Evidence and Apply Transitions in a short transaction
        final DestinationPlaylistInspection finalInspection = inspection;
        return transactionTemplate.execute(status -> {
            MigrationExecution execution = executionRepository.findById(executionId).orElseThrow();
            List<ExecutionTrackRecord> allRecords = trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId);

            // Collect all playlist item IDs already confirmed and assigned to ADDED tracks
            Set<String> assignedPlaylistItemIds = new java.util.HashSet<>();
            for (ExecutionTrackRecord r : allRecords) {
                if (r.getStatus() == ExecutionTrackStatus.ADDED && r.getDestinationPlaylistItemId() != null && !r.getDestinationPlaylistItemId().isBlank()) {
                    assignedPlaylistItemIds.add(r.getDestinationPlaylistItemId().trim());
                }
            }

            List<TrackReconciliationOutcomeDto> outcomes = new ArrayList<>();
            int confirmedInserted = 0;
            int confirmedNotInserted = 0;
            int confirmedDeleted = 0;
            int confirmedPresent = 0;
            int ambiguous = 0;
            int inspectionFailed = 0;

            for (ExecutionTrackRecordDto targetDto : claimedContext.targets()) {
                ExecutionTrackRecord track = allRecords.stream()
                        .filter(r -> r.getId().equals(targetDto.id()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Track record missing: " + targetDto.id()));

                ExecutionTrackStatus prevStatus = track.getStatus();
                ExecutionTrackStatus resolvedStatus = prevStatus;
                ReconciliationOutcome outcome;
                String evidenceSummary;
                String sanitizedError = null;

                // Determine ReconciliationType
                ReconciliationType recType;
                if (prevStatus == ExecutionTrackStatus.ROLLING_BACK) {
                    recType = ReconciliationType.STALE_IN_FLIGHT_ROLLING_BACK;
                } else if (prevStatus == ExecutionTrackStatus.ADDING) {
                    recType = ReconciliationType.STALE_IN_FLIGHT_ADDING;
                } else if (track.getDestinationPlaylistItemId() != null && !track.getDestinationPlaylistItemId().isBlank()) {
                    recType = ReconciliationType.DELETION;
                } else {
                    recType = ReconciliationType.INSERTION;
                }

                if (!finalInspection.complete()) {
                    outcome = ReconciliationOutcome.INSPECTION_FAILED;
                    sanitizedError = sanitize(finalInspection.failureReason());
                    evidenceSummary = "Remote playlist inspection incomplete or failed: " + sanitizedError;
                    track.reconcileAmbiguous(evidenceSummary);
                    resolvedStatus = track.getStatus();
                    inspectionFailed++;
                } else if (recType == ReconciliationType.DELETION || recType == ReconciliationType.STALE_IN_FLIGHT_ROLLING_BACK) {
                    // Deletion / Rollback Reconciliation
                    String targetPlaylistItemId = track.getDestinationPlaylistItemId();
                    if (targetPlaylistItemId == null || targetPlaylistItemId.isBlank()) {
                        outcome = ReconciliationOutcome.AMBIGUOUS;
                        evidenceSummary = "Ambiguous rollback: missing destinationPlaylistItemId on track record";
                        track.reconcileAmbiguous(evidenceSummary);
                        resolvedStatus = track.getStatus();
                        ambiguous++;
                    } else {
                        boolean itemPresent = finalInspection.items().stream()
                                .anyMatch(item -> targetPlaylistItemId.equals(item.playlistItemId()));

                        if (!itemPresent) {
                            // Item is absent from complete inspection -> confirmed deleted!
                            outcome = ReconciliationOutcome.CONFIRMED_DELETED;
                            evidenceSummary = "Verified absence of playlistItemId " + targetPlaylistItemId + " in complete inspection (" + finalInspection.totalItemCount() + " items)";
                            track.reconcileConfirmedDeleted(evidenceSummary);
                            resolvedStatus = track.getStatus();
                            confirmedDeleted++;
                        } else {
                            // Item is still present -> rollback did not happen or failed
                            outcome = ReconciliationOutcome.CONFIRMED_PRESENT;
                            evidenceSummary = "Verified presence of playlistItemId " + targetPlaylistItemId + " in remote playlist";
                            track.reconcileConfirmedPresent(evidenceSummary);
                            resolvedStatus = track.getStatus();
                            confirmedPresent++;
                        }
                    }
                } else {
                    // Insertion Reconciliation
                    String expectedVideoId = track.getDestinationTrackId();
                    List<DestinationPlaylistItemDetails> matchingItems = finalInspection.items().stream()
                            .filter(item -> expectedVideoId != null && expectedVideoId.equals(item.destinationTrackId()))
                            .toList();

                    // Filter out items already claimed by OTHER ADDED tracks
                    List<DestinationPlaylistItemDetails> unassignedItems = matchingItems.stream()
                            .filter(item -> !assignedPlaylistItemIds.contains(item.playlistItemId())
                                    || item.playlistItemId().equals(track.getDestinationPlaylistItemId()))
                            .toList();

                    if (unassignedItems.isEmpty()) {
                        // Confirmed absent from complete playlist
                        outcome = ReconciliationOutcome.CONFIRMED_NOT_INSERTED;
                        evidenceSummary = "Verified complete absence of unassigned video " + expectedVideoId + " across " + finalInspection.totalItemCount() + " playlist items";
                        track.reconcileConfirmedNotInserted(evidenceSummary);
                        resolvedStatus = track.getStatus();
                        confirmedNotInserted++;
                    } else if (unassignedItems.size() == 1) {
                        DestinationPlaylistItemDetails candidate = unassignedItems.get(0);
                        boolean timestampValid = true;
                        if (candidate.publishedAt() != null && track.getStartedAt() != null) {
                            // If candidate was added before attempt started (with 30s clock skew tolerance)
                            if (candidate.publishedAt().isBefore(track.getStartedAt().minusSeconds(30))) {
                                timestampValid = false;
                            }
                        }

                        if (!timestampValid) {
                            // Item pre-dates insertion attempt -> cannot prove this execution inserted it
                            outcome = ReconciliationOutcome.AMBIGUOUS;
                            evidenceSummary = "Ambiguous insertion: matching item " + candidate.playlistItemId() + " publishedAt (" + candidate.publishedAt() + ") pre-dates attempt startedAt (" + track.getStartedAt() + ")";
                            track.reconcileAmbiguous(evidenceSummary);
                            resolvedStatus = track.getStatus();
                            ambiguous++;
                        } else {
                            // Confirmed inserted!
                            outcome = ReconciliationOutcome.CONFIRMED_INSERTED;
                            evidenceSummary = "Verified single matching item " + candidate.playlistItemId() + " (position=" + candidate.position() + ", publishedAt=" + candidate.publishedAt() + ")";
                            track.reconcileConfirmedAdded(candidate.playlistItemId(), evidenceSummary);
                            assignedPlaylistItemIds.add(candidate.playlistItemId());
                            resolvedStatus = track.getStatus();
                            confirmedInserted++;
                        }
                    } else {
                        // Multiple unassigned items found with same video ID
                        outcome = ReconciliationOutcome.AMBIGUOUS;
                        evidenceSummary = "Ambiguous insertion: found " + unassignedItems.size() + " unassigned matching items for video " + expectedVideoId + "; cannot disambiguate";
                        track.reconcileAmbiguous(evidenceSummary);
                        resolvedStatus = track.getStatus();
                        ambiguous++;
                    }
                }

                trackRecordRepository.saveAndFlush(track);

                // Save Audit Record
                if (reconciliationRecordRepository != null) {
                    ExecutionReconciliationRecord auditRecord = new ExecutionReconciliationRecord(
                            execution,
                            track,
                            recType,
                            prevStatus,
                            resolvedStatus,
                            outcome,
                            claimedContext.destinationPlaylistId(),
                            track.getDestinationPlaylistItemId(),
                            track.getDestinationTrackId(),
                            evidenceSummary,
                            Instant.now(),
                            inspectionStartedAt,
                            inspectionCompletedAt,
                            outcome.name(),
                            sanitizedError
                    );
                    reconciliationRecordRepository.save(auditRecord);
                }

                outcomes.add(new TrackReconciliationOutcomeDto(
                        track.getTargetPosition(),
                        track.getSourceTrackId(),
                        track.getDestinationTrackId(),
                        track.getDestinationPlaylistItemId(),
                        recType,
                        prevStatus,
                        resolvedStatus,
                        outcome,
                        evidenceSummary,
                        sanitizedError,
                        Instant.now()
                ));
            }

            // Reconcile Execution State & Derived Counts
            execution.releaseExecution();
            MigrationExecution savedExecution = executionRepository.saveAndFlush(execution);

            boolean fullyResolved = savedExecution.getUncertainInsertions() == 0
                    && savedExecution.getStatus() != MigrationExecutionStatus.RECOVERY_REQUIRED;

            return new ExecutionReconciliationSummary(
                    executionId,
                    claimedContext.previousExecutionStatus(),
                    savedExecution.getStatus(),
                    claimedContext.targets().size(),
                    outcomes.size(),
                    confirmedInserted,
                    confirmedNotInserted,
                    confirmedDeleted,
                    confirmedPresent,
                    ambiguous,
                    inspectionFailed,
                    outcomes,
                    fullyResolved
            );
        });
    }

    @Override
    public List<ReconciliationRecordDto> getReconciliationHistory(UUID executionId, UUID authenticatedUserId) {
        if (executionId == null) {
            throw new IllegalArgumentException("Execution ID must not be null");
        }
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user ID must not be null");
        }

        return transactionTemplate.execute(status -> {
            MigrationExecution execution = executionRepository.findById(executionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Migration execution not found: " + executionId));

            if (!execution.getUser().getId().equals(authenticatedUserId)) {
                throw new ResourceNotFoundException("Migration execution not found: " + executionId);
            }

            if (reconciliationRecordRepository == null) {
                return Collections.emptyList();
            }

            return reconciliationRecordRepository.findByExecutionIdOrderByCreatedAtAsc(executionId).stream()
                    .map(ReconciliationRecordDto::from)
                    .toList();
        });
    }

    @Override
    public ExecutionReconciliationStatusDto getReconciliationStatus(UUID executionId, UUID authenticatedUserId) {
        if (executionId == null) {
            throw new IllegalArgumentException("Execution ID must not be null");
        }
        if (authenticatedUserId == null) {
            throw new IllegalArgumentException("Authenticated user ID must not be null");
        }

        return transactionTemplate.execute(status -> {
            MigrationExecution execution = executionRepository.findById(executionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Migration execution not found: " + executionId));

            if (!execution.getUser().getId().equals(authenticatedUserId)) {
                throw new ResourceNotFoundException("Migration execution not found: " + executionId);
            }

            // Evaluate stale state
            execution.reconcileStaleCrashState();

            List<ExecutionTrackRecord> allTracks = trackRecordRepository.findByExecutionIdOrderByTargetPositionAsc(executionId);
            Instant cutoff = Instant.now().minus(MigrationExecution.DEFAULT_STALE_THRESHOLD);

            List<ExecutionTrackRecordDto> eligible = allTracks.stream()
                    .filter(t -> t.getStatus() == ExecutionTrackStatus.UNCERTAIN
                            || (t.getStatus() == ExecutionTrackStatus.ADDING && t.isStaleInFlight(cutoff))
                            || (t.getStatus() == ExecutionTrackStatus.ROLLING_BACK && t.isStaleInFlight(cutoff)))
                    .map(ExecutionTrackRecordDto::from)
                    .toList();

            long staleInFlightCount = allTracks.stream()
                    .filter(t -> (t.getStatus() == ExecutionTrackStatus.ADDING || t.getStatus() == ExecutionTrackStatus.ROLLING_BACK) && t.isStaleInFlight(cutoff))
                    .count();

            boolean required = execution.getStatus() == MigrationExecutionStatus.RECOVERY_REQUIRED
                    || execution.getUncertainInsertions() > 0
                    || !eligible.isEmpty();

            return new ExecutionReconciliationStatusDto(
                    execution.getId(),
                    execution.getStatus(),
                    execution.getTotalApprovedTracks(),
                    execution.getSuccessfulInsertions(),
                    execution.getFailedInsertions(),
                    execution.getUncertainInsertions(),
                    execution.getRolledBackInsertions(),
                    (int) staleInFlightCount,
                    required,
                    eligible
            );
        });
    }
}
