package com.spotifyyoutube.migrator.migration.progress.domain;

/**
 * Exception thrown when migration progress state violates mathematical invariants (Phase 12F).
 * <p>
 * Signals count conservation failure, data drift between execution aggregates and track records,
 * or unrecognized track status outcomes.
 */
public class ProgressConsistencyException extends IllegalStateException {

    public ProgressConsistencyException(String message) {
        super(message);
    }

    public ProgressConsistencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
