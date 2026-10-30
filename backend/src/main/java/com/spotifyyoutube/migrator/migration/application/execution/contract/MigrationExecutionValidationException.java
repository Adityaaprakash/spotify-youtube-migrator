package com.spotifyyoutube.migrator.migration.application.execution.contract;

import com.spotifyyoutube.migrator.common.exception.InvalidStateException;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Domain exception thrown when an approved migration plan fails execution validation.
 * <p>
 * Extends {@link InvalidStateException} so that REST controllers naturally map to HTTP 409 Conflict
 * when execution cannot proceed due to inconsistent state or incomplete review.
 */
public class MigrationExecutionValidationException extends InvalidStateException {

    private final ExecutionValidationResult validationResult;

    public MigrationExecutionValidationException(ExecutionValidationResult validationResult) {
        super(formatMessage(validationResult));
        this.validationResult = Objects.requireNonNull(validationResult, "ExecutionValidationResult must not be null");
    }

    public MigrationExecutionValidationException(String message, ExecutionValidationResult validationResult) {
        super(message);
        this.validationResult = Objects.requireNonNull(validationResult, "ExecutionValidationResult must not be null");
    }

    public ExecutionValidationResult getValidationResult() {
        return validationResult;
    }

    public List<ExecutionValidationError> getErrors() {
        return validationResult.errors();
    }

    private static String formatMessage(ExecutionValidationResult result) {
        if (result == null || result.errors().isEmpty()) {
            return "Migration execution validation failed";
        }
        if (result.errors().size() == 1) {
            return result.errors().getFirst().message();
        }
        return String.format("Migration execution validation failed with %d errors: %s",
                result.errors().size(),
                result.errors().stream()
                        .map(ExecutionValidationError::message)
                        .collect(Collectors.joining("; "))
        );
    }
}
