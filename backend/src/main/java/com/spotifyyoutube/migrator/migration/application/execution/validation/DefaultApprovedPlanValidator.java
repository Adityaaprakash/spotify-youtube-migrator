package com.spotifyyoutube.migrator.migration.application.execution.validation;

import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedTrack;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationError;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationResult;
import com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionValidationException;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ValidationFailureCode;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlan;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlanTrack;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewDecisionSource;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSessionStatus;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrack;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewTrackStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure, stateless implementation of {@link ApprovedPlanValidator}.
 * <p>
 * Evaluates execution requests and completed review sessions against domain validation rules
 * prior to any destination mutations. Guarantees:
 * <ol>
 *   <li>Destination Isolation: Zero calls to external destination APIs or mutation methods.</li>
 *   <li>All-or-Nothing Safety: Ineligibility on even one track fails the entire validation.</li>
 *   <li>Order Determinism: Preserves relative source order and assigns gapless destination positions.</li>
 *   <li>Evidence Immutability: Reads decisions without altering review domain state.</li>
 *   <li>Ownership Security: Verifies authenticated user ownership and prevents impersonation.</li>
 * </ol>
 */
@Component
public class DefaultApprovedPlanValidator implements ApprovedPlanValidator {

    private static final Logger log = LoggerFactory.getLogger(DefaultApprovedPlanValidator.class);

    @Override
    public ExecutionValidationResult validate(
            ReviewSession session,
            ApprovedMigrationExecutionRequest request,
            UUID authenticatedUserId
    ) {
        List<ExecutionValidationError> errors = new ArrayList<>();

        // 1. Request and Basic Context Validity
        if (session == null) {
            return ExecutionValidationResult.invalid(
                    ValidationFailureCode.MISSING_CONTEXT,
                    "Review session must not be null"
            );
        }
        if (authenticatedUserId == null) {
            return ExecutionValidationResult.invalid(
                    ValidationFailureCode.UNAUTHORIZED,
                    "Authenticated user ID must not be null"
            );
        }

        ApprovedMigrationExecutionRequest effectiveRequest = request != null
                ? request
                : ApprovedMigrationExecutionRequest.of(session.getId(), authenticatedUserId);

        // 2. Ownership & Anti-IDOR Authorization
        if (session.getUser() == null || session.getUser().getId() == null) {
            errors.add(ExecutionValidationError.sessionLevel(
                    ValidationFailureCode.MISSING_CONTEXT,
                    "Review session owner is missing or null"
            ));
        } else if (!Objects.equals(session.getUser().getId(), authenticatedUserId)) {
            errors.add(ExecutionValidationError.sessionLevel(
                    ValidationFailureCode.UNAUTHORIZED,
                    String.format("User %s is not authorized to execute review session %s owned by user %s",
                            authenticatedUserId, session.getId(), session.getUser().getId())
            ));
        }

        // Anti-impersonation check on requested userId
        if (effectiveRequest.getUserId().isPresent()) {
            UUID requestedUserId = effectiveRequest.getUserId().get();
            if (!Objects.equals(requestedUserId, authenticatedUserId)) {
                errors.add(ExecutionValidationError.sessionLevel(
                        ValidationFailureCode.UNAUTHORIZED,
                        String.format("Requested user ID %s does not match authenticated user %s",
                                requestedUserId, authenticatedUserId)
                ));
            }
        }

        // 3. Review Session Lifecycle Status
        if (session.getStatus() == ReviewSessionStatus.IN_PROGRESS) {
            errors.add(ExecutionValidationError.sessionLevel(
                    ValidationFailureCode.INCOMPLETE_REVIEW,
                    String.format("Review session %s is still in progress and cannot be executed", session.getId())
            ));
        } else if (session.getStatus() == ReviewSessionStatus.CANCELLED) {
            errors.add(ExecutionValidationError.sessionLevel(
                    ValidationFailureCode.INVALID_STATE,
                    String.format("Review session %s has been cancelled and cannot be executed", session.getId())
            ));
        } else if (session.getStatus() != ReviewSessionStatus.COMPLETED) {
            errors.add(ExecutionValidationError.sessionLevel(
                    ValidationFailureCode.INVALID_STATE,
                    String.format("Review session %s is in unexpected status: %s", session.getId(), session.getStatus())
            ));
        }

        // 4. Source Playlist & Plan Context Consistency
        if (effectiveRequest.getSourcePlaylistId().isPresent()) {
            String reqPlaylistId = effectiveRequest.getSourcePlaylistId().get();
            if (!Objects.equals(reqPlaylistId, session.getSourcePlaylistId())) {
                errors.add(ExecutionValidationError.sessionLevel(
                        ValidationFailureCode.INCONSISTENT_CONTEXT,
                        String.format("Requested source playlist ID '%s' does not match session source playlist ID '%s'",
                                reqPlaylistId, session.getSourcePlaylistId())
                ));
            }
        }

        if (effectiveRequest.getPlanId().isPresent() && session.getPlanId() != null) {
            UUID reqPlanId = effectiveRequest.getPlanId().get();
            if (!Objects.equals(reqPlanId, session.getPlanId())) {
                errors.add(ExecutionValidationError.sessionLevel(
                        ValidationFailureCode.INCONSISTENT_CONTEXT,
                        String.format("Requested plan ID %s does not match session plan ID %s",
                                reqPlanId, session.getPlanId())
                ));
            }
        }

        // 5. Transient MigrationPlan Cross-Validation (if provided)
        MigrationPlan planToCheck = effectiveRequest.getPlan().orElse(null);
        if (planToCheck != null) {
            if (session.getPlanId() != null && !Objects.equals(planToCheck.planId(), session.getPlanId())) {
                errors.add(ExecutionValidationError.sessionLevel(
                        ValidationFailureCode.INCONSISTENT_CONTEXT,
                        String.format("MigrationPlan planId %s does not match session planId %s",
                                planToCheck.planId(), session.getPlanId())
                ));
            }
            if (!Objects.equals(planToCheck.sourcePlaylistId(), session.getSourcePlaylistId())) {
                errors.add(ExecutionValidationError.sessionLevel(
                        ValidationFailureCode.INCONSISTENT_CONTEXT,
                        String.format("MigrationPlan sourcePlaylistId '%s' does not match session sourcePlaylistId '%s'",
                                planToCheck.sourcePlaylistId(), session.getSourcePlaylistId())
                ));
            }
            if (session.getTracks() != null && planToCheck.tracks().size() != session.getTracks().size()) {
                errors.add(ExecutionValidationError.sessionLevel(
                        ValidationFailureCode.INCONSISTENT_CONTEXT,
                        String.format("MigrationPlan track count (%d) does not match session track count (%d)",
                                planToCheck.tracks().size(), session.getTracks().size())
                ));
            }
        }

        List<ReviewTrack> tracks = session.getTracks() != null ? session.getTracks() : List.of();

        // Total tracks count consistency check
        if (session.getTotalTracks() != null && session.getTotalTracks() > 0 && session.getTotalTracks() != tracks.size()) {
            errors.add(ExecutionValidationError.sessionLevel(
                    ValidationFailureCode.ORDERING_MISMATCH,
                    String.format("Session totalTracks count (%d) does not match loaded tracks count (%d)",
                            session.getTotalTracks(), tracks.size())
            ));
        }

        // 6. Deterministic Source Ordering & Track Eligibility
        List<ApprovedTrack> approvedTracks = new ArrayList<>();
        Set<String> seenCandidateIds = new HashSet<>();
        Map<String, Integer> seenPositions = new HashMap<>();
        int excludedCount = 0;

        for (int i = 0; i < tracks.size(); i++) {
            ReviewTrack track = tracks.get(i);
            if (track == null) {
                errors.add(ExecutionValidationError.trackLevel(
                        ValidationFailureCode.MISSING_CONTEXT, i, null,
                        "Track at position " + i + " is null"
                ));
                continue;
            }

            // Invariant: strict 0-indexed position monotonicity
            if (track.getPosition() != i) {
                errors.add(ExecutionValidationError.trackLevel(
                        ValidationFailureCode.ORDERING_MISMATCH, track.getPosition(), track.getSourceTrackId(),
                        String.format("Track at array index %d has non-contiguous position %d", i, track.getPosition())
                ));
            }

            // Cross-plan track association check
            if (planToCheck != null && i < planToCheck.tracks().size()) {
                MigrationPlanTrack planTrack = planToCheck.tracks().get(i);
                if (!Objects.equals(track.getSourceTrackId(), planTrack.sourceTrackId())) {
                    errors.add(ExecutionValidationError.trackLevel(
                            ValidationFailureCode.CANDIDATE_ASSOCIATION_MISMATCH,
                            track.getPosition(), track.getSourceTrackId(),
                            String.format("Review track sourceTrackId '%s' does not match plan track sourceTrackId '%s' at position %d",
                                    track.getSourceTrackId(), planTrack.sourceTrackId(), i)
                    ));
                }
            }

            // Case A: Excluded Track
            if (track.isExcluded() || track.getReviewStatus() == ReviewTrackStatus.EXCLUDED) {
                excludedCount++;
                if (track.isApprovedForMigration()) {
                    errors.add(ExecutionValidationError.trackLevel(
                            ValidationFailureCode.INVALID_STATE,
                            track.getPosition(), track.getSourceTrackId(),
                            String.format("Track at position %d is excluded but reports isApprovedForMigration() == true", i)
                    ));
                }
                continue; // Excluded track must not appear in approved selection
            }

            // Case B: Unresolved / Awaiting Review Track
            if (track.getReviewStatus() == ReviewTrackStatus.AWAITING_REVIEW || track.isUnresolved()) {
                errors.add(ExecutionValidationError.trackLevel(
                        ValidationFailureCode.UNRESOLVED_TRACK,
                        track.getPosition(), track.getSourceTrackId(),
                        String.format("Track at position %d ('%s') is unresolved or awaiting review and cannot be executed",
                                i, track.getSourceTitle())
                ));
                continue;
            }

            // Case C: Unresolved Duplicate Conflict
            if (track.hasConflict()) {
                String conflictMsg = track.getConflictReason() != null ? track.getConflictReason() : "duplicate destination candidate";
                errors.add(ExecutionValidationError.trackLevel(
                        ValidationFailureCode.UNRESOLVED_CONFLICT,
                        track.getPosition(), track.getSourceTrackId(),
                        String.format("Track at position %d ('%s') has an unresolved conflict: %s",
                                i, track.getSourceTitle(), conflictMsg)
                ));
                continue;
            }

            // Case D: Accepted Automatic Match or User-Selected Candidate
            if (track.getReviewStatus() == ReviewTrackStatus.ACCEPTED || track.getReviewStatus() == ReviewTrackStatus.USER_SELECTED) {
                String candidateId = track.getSelectedCandidateId();
                if (candidateId == null || candidateId.isBlank()) {
                    errors.add(ExecutionValidationError.trackLevel(
                            ValidationFailureCode.INVALID_DESTINATION_ID,
                            track.getPosition(), track.getSourceTrackId(),
                            String.format("Track at position %d ('%s') is marked %s but has a missing or blank destination candidate ID",
                                    i, track.getSourceTitle(), track.getReviewStatus())
                    ));
                    continue;
                }

                if (!track.isApprovedForMigration()) {
                    errors.add(ExecutionValidationError.trackLevel(
                            ValidationFailureCode.INVALID_STATE,
                            track.getPosition(), track.getSourceTrackId(),
                            String.format("Track at position %d ('%s') is marked %s but reports isApprovedForMigration() == false",
                                    i, track.getSourceTitle(), track.getReviewStatus())
                    ));
                    continue;
                }

                String trimmedCandidateId = candidateId.trim();

                // Prevent duplicate destination tracks across the approved selection
                if (seenCandidateIds.contains(trimmedCandidateId)) {
                    int prevPos = seenPositions.get(trimmedCandidateId);
                    errors.add(ExecutionValidationError.trackLevel(
                            ValidationFailureCode.DUPLICATE_DESTINATION_TRACK,
                            track.getPosition(), track.getSourceTrackId(),
                            String.format("Duplicate destination track ID '%s' at position %d already claimed by track at position %d",
                                    trimmedCandidateId, i, prevPos)
                    ));
                    continue;
                }

                // User-selected candidate integrity validation
                if (track.getReviewStatus() == ReviewTrackStatus.USER_SELECTED) {
                    if (track.getSelectedCandidateTitle() == null || track.getSelectedCandidateTitle().isBlank()) {
                        errors.add(ExecutionValidationError.trackLevel(
                                ValidationFailureCode.INVALID_CANDIDATE,
                                track.getPosition(), track.getSourceTrackId(),
                                String.format("User-selected candidate at position %d ('%s') has a null or blank title",
                                        i, track.getSourceTitle())
                        ));
                        continue;
                    }
                    if (track.getSelectedCandidateDurationMs() != null && track.getSelectedCandidateDurationMs() < 0) {
                        errors.add(ExecutionValidationError.trackLevel(
                                ValidationFailureCode.INVALID_CANDIDATE,
                                track.getPosition(), track.getSourceTrackId(),
                                String.format("User-selected candidate at position %d has a negative duration: %d ms",
                                        i, track.getSelectedCandidateDurationMs())
                        ));
                        continue;
                    }
                }

                seenCandidateIds.add(trimmedCandidateId);
                seenPositions.put(trimmedCandidateId, i);

                ReviewDecisionSource decisionSource = track.getDecisionSource() != null
                        ? track.getDecisionSource()
                        : (track.getReviewStatus() == ReviewTrackStatus.USER_SELECTED ? ReviewDecisionSource.USER_SELECTED : ReviewDecisionSource.AUTOMATIC);

                Duration duration = track.getSelectedCandidateDurationMs() != null
                        ? Duration.ofMillis(track.getSelectedCandidateDurationMs())
                        : null;

                ApprovedTrack approvedTrack = new ApprovedTrack(
                        track.getPosition(),
                        track.getSourceTrackId(),
                        track.getSourceTitle(),
                        track.getSourceArtist(),
                        trimmedCandidateId,
                        track.getSelectedCandidateTitle(),
                        track.getSelectedCandidateChannel(),
                        duration,
                        decisionSource,
                        approvedTracks.size() // 0-indexed sequential destination position
                );
                approvedTracks.add(approvedTrack);
            } else {
                errors.add(ExecutionValidationError.trackLevel(
                        ValidationFailureCode.INVALID_STATE,
                        track.getPosition(), track.getSourceTrackId(),
                        String.format("Track at position %d has unexpected review status: %s", i, track.getReviewStatus())
                ));
            }
        }

        // Return invalid result if any errors occurred
        if (!errors.isEmpty()) {
            log.warn("Validation failed for review session {} with {} errors", session.getId(), errors.size());
            return ExecutionValidationResult.invalid(errors);
        }

        // 7. Assemble Valid ApprovedMigrationContext
        String targetTitle = effectiveRequest.getTargetPlaylistTitle()
                .filter(t -> !t.isBlank())
                .orElse("Migrated - " + session.getSourcePlaylistTitle());

        String targetDesc = effectiveRequest.getTargetPlaylistDescription()
                .filter(d -> !d.isBlank())
                .orElse("Migrated from Spotify");

        boolean isPrivate = effectiveRequest.isPrivatePlaylist();

        ApprovedMigrationContext context = new ApprovedMigrationContext(
                session.getId(),
                session.getPlanId(),
                authenticatedUserId,
                session.getSourcePlaylistId(),
                session.getSourcePlaylistTitle(),
                targetTitle,
                targetDesc,
                isPrivate,
                approvedTracks,
                tracks.size(),
                approvedTracks.size(),
                excludedCount,
                Instant.now()
        );

        log.info("Validation succeeded for review session {}: {} approved tracks, {} excluded tracks",
                session.getId(), context.approvedTrackCount(), context.excludedTrackCount());
        return ExecutionValidationResult.valid(context);
    }

    @Override
    public ExecutionValidationResult validate(ReviewSession session, UUID authenticatedUserId) {
        return validate(session, null, authenticatedUserId);
    }

    @Override
    public ExecutionValidationResult validate(ReviewSession session, MigrationPlan plan, UUID authenticatedUserId) {
        Objects.requireNonNull(session, "session must not be null");
        ApprovedMigrationExecutionRequest req = ApprovedMigrationExecutionRequest.ofSessionAndPlan(
                session.getId(), authenticatedUserId, plan
        );
        return validate(session, req, authenticatedUserId);
    }

    @Override
    public ApprovedMigrationContext validateAndApprove(
            ReviewSession session,
            ApprovedMigrationExecutionRequest request,
            UUID authenticatedUserId
    ) {
        ExecutionValidationResult result = validate(session, request, authenticatedUserId);
        if (!result.isValid()) {
            throw new MigrationExecutionValidationException(result);
        }
        return result.approvedContext();
    }

    @Override
    public ApprovedMigrationContext validateAndApprove(ReviewSession session, UUID authenticatedUserId) {
        return validateAndApprove(session, null, authenticatedUserId);
    }

    @Override
    public ApprovedMigrationContext validateAndApprove(ReviewSession session, MigrationPlan plan, UUID authenticatedUserId) {
        Objects.requireNonNull(session, "session must not be null");
        ApprovedMigrationExecutionRequest req = ApprovedMigrationExecutionRequest.ofSessionAndPlan(
                session.getId(), authenticatedUserId, plan
        );
        return validateAndApprove(session, req, authenticatedUserId);
    }
}
