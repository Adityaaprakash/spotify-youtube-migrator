package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.common.exception.ExternalProviderException;
import com.spotifyyoutube.migrator.common.exception.InvalidStateException;
import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.identity.domain.User;
import com.spotifyyoutube.migrator.migration.application.destination.MusicDestination;
import com.spotifyyoutube.migrator.migration.application.destination.model.AddTrackToDestinationPlaylistRequest;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationPlaylistItem;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionTrackRecordDto;
import com.spotifyyoutube.migrator.migration.application.execution.contract.InitializeExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionSummary;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackRecord;
import com.spotifyyoutube.migrator.migration.execution.domain.ExecutionTrackStatus;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecution;
import com.spotifyyoutube.migrator.migration.execution.domain.MigrationExecutionStatus;
import com.spotifyyoutube.migrator.migration.execution.repository.ExecutionTrackRecordRepository;
import com.spotifyyoutube.migrator.migration.execution.repository.MigrationExecutionRepository;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Default implementation of {@link MigrationExecutionStateService}.
 * <p>
 * Enforces discrete, short database transactions around remote mutations, concurrency claims,
 * optimistic locking, crash state reconciliation, and safe resume operations.
 */
@Service
public class DefaultMigrationExecutionStateService implements MigrationExecutionStateService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMigrationExecutionStateService.class);

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)(bearer\\s+|(?:access_token|refresh_token|token|api_key|apikey|key|client_secret|secret|password)=)[^\\s&\"',;]+"
    );

    private final MigrationExecutionRepository executionRepository;
    private final ExecutionTrackRecordRepository trackRecordRepository;
    private final ReviewSessionRepository reviewSessionRepository;
    private final ApprovedMigrationExecutionService executionService;
    private final MusicDestination musicDestination;
    private final TransactionTemplate transactionTemplate;

    public DefaultMigrationExecutionStateService(
            MigrationExecutionRepository executionRepository,
            ExecutionTrackRecordRepository trackRecordRepository,
            ReviewSessionRepository reviewSessionRepository,
            ApprovedMigrationExecutionService executionService,
            MusicDestination musicDestination,
            TransactionTemplate transactionTemplate
    ) {
        this.executionRepository = Objects.requireNonNull(executionRepository, "MigrationExecutionRepository must not be null");
        this.trackRecordRepository = Objects.requireNonNull(trackRecordRepository, "ExecutionTrackRecordRepository must not be null");
        this.reviewSessionRepository = Objects.requireNonNull(reviewSessionRepository, "ReviewSessionRepository must not be null");
        this.executionService = Objects.requireNonNull(executionService, "ApprovedMigrationExecutionService must not be null");
        this.musicDestination = Objects.requireNonNull(musicDestination, "MusicDestination must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "TransactionTemplate must not be null");
    }

    @org.springframework.beans.factory.annotation.Autowired
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
                reviewSessionRepository,
                executionService,
                musicDestination,
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
            String playlistUrl = request.destinationPlaylistUrl().orElse(null);

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
            com.spotifyyoutube.migrator.migration.application.execution.contract.InsertTracksExecutionRequest request,
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

            // Claim execution lock (transitions to RUNNING)
            execution.claimExecution();
            executionRepository.saveAndFlush(execution);

            return execution.getDestinationPlaylistId();
        });

        // 2. Load track records ordered by canonical target position
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

        // 3. Sequential Insertion with Short Per-Track Transactions
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
                String sanitizedMsg = sanitize(ex.getMessage());
                log.error("Destination provider rejected track insertion for track {} in execution {}: {}",
                        track.destinationTrackId(), executionId, sanitizedMsg);

                transactionTemplate.execute(status -> {
                    ExecutionTrackRecord record = trackRecordRepository.findById(trackId)
                            .orElseThrow(() -> new IllegalStateException("Track record missing: " + trackId));
                    record.markFailed(sanitizedMsg);
                    trackRecordRepository.saveAndFlush(record);
                    return null;
                });
                stopRemaining = true;
                stopReason = "provider rejection";
                break;
            } catch (Exception ex) {
                String sanitizedMsg = sanitize(ex.getMessage());
                log.error("Unexpected error inserting track {} in execution {}: {}",
                        track.destinationTrackId(), executionId, sanitizedMsg);

                transactionTemplate.execute(status -> {
                    ExecutionTrackRecord record = trackRecordRepository.findById(trackId)
                            .orElseThrow(() -> new IllegalStateException("Track record missing: " + trackId));
                    record.markFailed("Unexpected insertion error: " + sanitizedMsg);
                    trackRecordRepository.saveAndFlush(record);
                    return null;
                });
                stopRemaining = true;
                stopReason = "unexpected error";
                break;
            }
        }

        // 4. If aborted, mark remaining PENDING tracks as SKIPPED
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

        // 5. Release execution claim, reconcile counts, and finalize status
        return transactionTemplate.execute(status -> {
            MigrationExecution execution = executionRepository.findById(executionId)
                    .orElseThrow(() -> new IllegalStateException("Migration execution missing: " + executionId));

            execution.releaseExecution();
            MigrationExecution saved = executionRepository.saveAndFlush(execution);

            log.info("Execution {} finalized with status {}: {} added, {} failed, {} uncertain, {} skipped out of {} total",
                    saved.getId(), saved.getStatus(), saved.getSuccessfulInsertions(),
                    saved.getFailedInsertions(), saved.getUncertainInsertions(),
                    saved.getSkippedInsertions(), saved.getTotalApprovedTracks());

            return MigrationExecutionSummary.from(saved);
        });
    }

    private String sanitize(String message) {
        if (message == null) {
            return "null";
        }
        return TOKEN_PATTERN.matcher(message).replaceAll("$1[REDACTED]");
    }
}
