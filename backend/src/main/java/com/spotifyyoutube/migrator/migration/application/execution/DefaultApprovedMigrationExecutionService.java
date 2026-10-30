package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.common.exception.ResourceNotFoundException;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ValidationFailureCode;
import com.spotifyyoutube.migrator.migration.application.execution.validation.ApprovedPlanValidator;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewSessionRepository;
import com.spotifyyoutube.migrator.migration.review.repository.ReviewTrackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Default implementation of {@link ApprovedMigrationExecutionService}.
 * <p>
 * Enforces transaction boundaries and server-side authorization checks before delegating
 * to {@link ApprovedPlanValidator}. Performs strictly zero destination mutations.
 */
@Service
@Transactional(readOnly = true)
public class DefaultApprovedMigrationExecutionService implements ApprovedMigrationExecutionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultApprovedMigrationExecutionService.class);

    private final ReviewSessionRepository reviewSessionRepository;
    private final ReviewTrackRepository reviewTrackRepository;
    private final ApprovedPlanValidator approvedPlanValidator;

    public DefaultApprovedMigrationExecutionService(
            ReviewSessionRepository reviewSessionRepository,
            ReviewTrackRepository reviewTrackRepository,
            ApprovedPlanValidator approvedPlanValidator
    ) {
        this.reviewSessionRepository = Objects.requireNonNull(reviewSessionRepository, "ReviewSessionRepository must not be null");
        this.reviewTrackRepository = Objects.requireNonNull(reviewTrackRepository, "ReviewTrackRepository must not be null");
        this.approvedPlanValidator = Objects.requireNonNull(approvedPlanValidator, "ApprovedPlanValidator must not be null");
    }

    @Override
    public ExecutionValidationResult validateExecution(ApprovedMigrationExecutionRequest request, UUID authenticatedUserId) {
        if (request == null) {
            return ExecutionValidationResult.invalid(ValidationFailureCode.MISSING_CONTEXT, "Execution request must not be null");
        }
        if (authenticatedUserId == null) {
            return ExecutionValidationResult.invalid(ValidationFailureCode.UNAUTHORIZED, "Authenticated user ID must not be null");
        }

        UUID sessionId = request.reviewSessionId();
        ReviewSession session = reviewSessionRepository.findById(sessionId)
                .orElse(null);

        if (session == null) {
            return ExecutionValidationResult.invalid(
                    ValidationFailureCode.MISSING_CONTEXT,
                    "Review session not found with id: " + sessionId
            );
        }

        // Anti-IDOR: verify ownership before proceeding with validation
        if (session.getUser() == null || !Objects.equals(session.getUser().getId(), authenticatedUserId)) {
            return ExecutionValidationResult.invalid(
                    ValidationFailureCode.UNAUTHORIZED,
                    String.format("User %s is not authorized to execute review session %s", authenticatedUserId, sessionId)
            );
        }

        ensureTracksLoaded(session);
        return approvedPlanValidator.validate(session, request, authenticatedUserId);
    }

    @Override
    public ExecutionValidationResult validateExecution(UUID reviewSessionId, UUID authenticatedUserId) {
        if (reviewSessionId == null) {
            return ExecutionValidationResult.invalid(ValidationFailureCode.MISSING_CONTEXT, "Review session ID must not be null");
        }
        ApprovedMigrationExecutionRequest req = ApprovedMigrationExecutionRequest.of(reviewSessionId, authenticatedUserId);
        return validateExecution(req, authenticatedUserId);
    }

    @Override
    public ApprovedMigrationContext prepareExecution(ApprovedMigrationExecutionRequest request, UUID authenticatedUserId) {
        Objects.requireNonNull(request, "Execution request must not be null");
        Objects.requireNonNull(authenticatedUserId, "Authenticated user ID must not be null");

        UUID sessionId = request.reviewSessionId();
        ReviewSession session = reviewSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Review session not found with id: " + sessionId));

        // Anti-IDOR: throw ResourceNotFoundException to prevent existence disclosure
        if (session.getUser() == null || !Objects.equals(session.getUser().getId(), authenticatedUserId)) {
            throw new ResourceNotFoundException("Review session not found with id: " + sessionId);
        }

        ensureTracksLoaded(session);
        return approvedPlanValidator.validateAndApprove(session, request, authenticatedUserId);
    }

    @Override
    public ApprovedMigrationContext prepareExecution(UUID reviewSessionId, UUID authenticatedUserId) {
        Objects.requireNonNull(reviewSessionId, "Review session ID must not be null");
        ApprovedMigrationExecutionRequest req = ApprovedMigrationExecutionRequest.of(reviewSessionId, authenticatedUserId);
        return prepareExecution(req, authenticatedUserId);
    }

    private List<ReviewTrack> ensureTracksLoaded(ReviewSession session) {
        List<ReviewTrack> tracks = session.getTracks();
        if (tracks == null || tracks.isEmpty()
                || (session.getTotalTracks() != null && session.getTotalTracks() > 0 && tracks.size() != session.getTotalTracks())) {
            if (session.getId() != null) {
                tracks = reviewTrackRepository.findBySessionIdOrderByPositionAsc(session.getId());
                session.setTracks(tracks);
            }
        }
        return tracks != null ? tracks : List.of();
    }
}
