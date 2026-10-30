package com.spotifyyoutube.migrator.migration.application.execution;

import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationContext;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ApprovedMigrationExecutionRequest;
import com.spotifyyoutube.migrator.migration.application.execution.contract.ExecutionValidationResult;

import java.util.UUID;

/**
 * Application service coordinating the validation and preparation of approved playlist migrations.
 * <p>
 * Bridges Phase 10 completed review sessions and the Phase 11 execution pipeline.
 * Performs zero destination-side mutations (no YouTube playlists or videos created).
 */
public interface ApprovedMigrationExecutionService {

    /**
     * Validates an approved migration execution request against the authenticated user.
     *
     * @param request execution parameters and session identifier
     * @param authenticatedUserId ID of the authenticated user
     * @return typed ExecutionValidationResult
     */
    ExecutionValidationResult validateExecution(ApprovedMigrationExecutionRequest request, UUID authenticatedUserId);

    /**
     * Validates a review session for execution by session ID.
     *
     * @param reviewSessionId unique review session identifier
     * @param authenticatedUserId ID of the authenticated user
     * @return typed ExecutionValidationResult
     */
    ExecutionValidationResult validateExecution(UUID reviewSessionId, UUID authenticatedUserId);

    /**
     * Validates and prepares an approved migration context for execution.
     * Throws {@link com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionValidationException}
     * if the session or track selection is ineligible.
     *
     * @param request execution parameters and session identifier
     * @param authenticatedUserId ID of the authenticated user
     * @return immutable ApprovedMigrationContext ready for destination creation
     */
    ApprovedMigrationContext prepareExecution(ApprovedMigrationExecutionRequest request, UUID authenticatedUserId);

    /**
     * Validates and prepares an approved migration context by session ID.
     * Throws {@link com.spotifyyoutube.migrator.migration.application.execution.contract.MigrationExecutionValidationException}
     * if the session or track selection is ineligible.
     *
     * @param reviewSessionId unique review session identifier
     * @param authenticatedUserId ID of the authenticated user
     * @return immutable ApprovedMigrationContext ready for destination creation
     */
    ApprovedMigrationContext prepareExecution(UUID reviewSessionId, UUID authenticatedUserId);
}
