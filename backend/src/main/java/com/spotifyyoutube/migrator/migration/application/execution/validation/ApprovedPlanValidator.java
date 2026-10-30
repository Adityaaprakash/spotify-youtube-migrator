package com.spotifyyoutube.migrator.migration.application.execution.validation;

import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationResult;
import com.spotifyyoutube.migrator.migration.application.plan.MigrationPlan;
import com.spotifyyoutube.migrator.migration.review.domain.ReviewSession;

import java.util.UUID;

/**
 * Dedicated validation boundary evaluating whether an approved migration is eligible for execution
 * prior to any destination-side mutations.
 * <p>
 * Enforces all 12 validation rules across request validity, session state, ownership authorization,
 * track eligibility, destination identity validity, duplicate prevention, and deterministic ordering.
 */
public interface ApprovedPlanValidator {

    /**
     * Validates a review session and execution request against the authenticated user security context.
     *
     * @param session review session containing decisions
     * @param request execution parameters and metadata overrides
     * @param authenticatedUserId ID of the authenticated user
     * @return typed ExecutionValidationResult
     */
    ExecutionValidationResult validate(ReviewSession session, ApprovedMigrationExecutionRequest request, UUID authenticatedUserId);

    /**
     * Validates a review session for execution using default parameters.
     *
     * @param session review session containing decisions
     * @param authenticatedUserId ID of the authenticated user
     * @return typed ExecutionValidationResult
     */
    ExecutionValidationResult validate(ReviewSession session, UUID authenticatedUserId);

    /**
     * Validates a review session against an in-memory transient MigrationPlan snapshot.
     *
     * @param session review session containing decisions
     * @param plan transient Phase 9 MigrationPlan snapshot
     * @param authenticatedUserId ID of the authenticated user
     * @return typed ExecutionValidationResult
     */
    ExecutionValidationResult validate(ReviewSession session, MigrationPlan plan, UUID authenticatedUserId);

    /**
     * Validates a review session and returns the ApprovedMigrationContext, throwing
     * {@link com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionValidationException}
     * if validation fails.
     *
     * @param session review session containing decisions
     * @param request execution parameters and metadata overrides
     * @param authenticatedUserId ID of the authenticated user
     * @return immutable ApprovedMigrationContext
     */
    ApprovedMigrationContext validateAndApprove(ReviewSession session, ApprovedMigrationExecutionRequest request, UUID authenticatedUserId);

    /**
     * Validates a review session with default request parameters, throwing on failure.
     *
     * @param session review session containing decisions
     * @param authenticatedUserId ID of the authenticated user
     * @return immutable ApprovedMigrationContext
     */
    ApprovedMigrationContext validateAndApprove(ReviewSession session, UUID authenticatedUserId);

    /**
     * Validates a review session against an in-memory transient MigrationPlan, throwing on failure.
     *
     * @param session review session containing decisions
     * @param plan transient Phase 9 MigrationPlan snapshot
     * @param authenticatedUserId ID of the authenticated user
     * @return immutable ApprovedMigrationContext
     */
    ApprovedMigrationContext validateAndApprove(ReviewSession session, MigrationPlan plan, UUID authenticatedUserId);
}
