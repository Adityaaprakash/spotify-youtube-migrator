package com.spotifyyoutube.migrator.migration.application.execution.contract;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable typed result of an approved migration execution validation.
 *
 * @param valid true if all validation invariants passed and the migration is eligible for execution
 * @param approvedContext validated execution context containing approved tracks, or null if invalid
 * @param errors unmodifiable list of validation errors explaining why execution is ineligible
 */
public record ExecutionValidationResult(
        boolean valid,
        ApprovedMigrationContext approvedContext,
        List<ExecutionValidationError> errors
) {

    public ExecutionValidationResult {
        errors = errors != null ? List.copyOf(errors) : List.of();
        if (valid && approvedContext == null) {
            throw new IllegalArgumentException("Valid validation result must contain a non-null ApprovedMigrationContext");
        }
        if (valid && !errors.isEmpty()) {
            throw new IllegalArgumentException("Valid validation result cannot contain validation errors");
        }
        if (!valid && errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid validation result must contain at least one validation error");
        }
    }

    public static ExecutionValidationResult valid(ApprovedMigrationContext context) {
        Objects.requireNonNull(context, "ApprovedMigrationContext must not be null");
        return new ExecutionValidationResult(true, context, List.of());
    }

    public static ExecutionValidationResult invalid(List<ExecutionValidationError> errors) {
        Objects.requireNonNull(errors, "Errors list must not be null");
        if (errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid result must have at least one error");
        }
        return new ExecutionValidationResult(false, null, errors);
    }

    public static ExecutionValidationResult invalid(ValidationFailureCode code, String message) {
        return invalid(List.of(ExecutionValidationError.sessionLevel(code, message)));
    }

    public static ExecutionValidationResult invalid(ValidationFailureCode code, int position, String sourceTrackId, String message) {
        return invalid(List.of(ExecutionValidationError.trackLevel(code, position, sourceTrackId, message)));
    }

    public Optional<ApprovedMigrationContext> getApprovedContext() {
        return Optional.ofNullable(approvedContext);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public String firstErrorMessage() {
        return errors.isEmpty() ? "" : errors.getFirst().message();
    }
}
