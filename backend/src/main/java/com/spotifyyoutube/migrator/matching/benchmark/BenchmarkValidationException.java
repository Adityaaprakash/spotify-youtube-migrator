package com.spotifyyoutube.migrator.matching.benchmark;

import java.util.List;

/**
 * Exception thrown when a benchmark dataset fails validation against its schema contract.
 */
public class BenchmarkValidationException extends RuntimeException {

    private final List<String> validationErrors;

    /**
     * Constructs a BenchmarkValidationException with detailed error list.
     *
     * @param message overall summary message
     * @param validationErrors itemized validation error list
     */
    public BenchmarkValidationException(String message, List<String> validationErrors) {
        super(formatMessage(message, validationErrors));
        this.validationErrors = validationErrors != null ? List.copyOf(validationErrors) : List.of();
    }

    /**
     * Constructs a BenchmarkValidationException with a single error message.
     *
     * @param message error message
     */
    public BenchmarkValidationException(String message) {
        this(message, List.of(message));
    }

    public List<String> getValidationErrors() {
        return validationErrors;
    }

    private static String formatMessage(String message, List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return message;
        }
        return message + ": " + String.join("; ", errors);
    }
}
