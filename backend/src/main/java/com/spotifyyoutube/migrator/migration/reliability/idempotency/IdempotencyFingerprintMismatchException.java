package com.spotifyyoutube.migrator.migration.reliability.idempotency;

import com.spotifyyoutube.migrator.common.exception.ConflictException;

/**
 * Thrown when an incoming migration submission uses an existing idempotency key
 * but has a conflicting or differing canonical request fingerprint (Phase 13E).
 */
public class IdempotencyFingerprintMismatchException extends ConflictException {

    public IdempotencyFingerprintMismatchException(String message) {
        super(message);
    }
}
