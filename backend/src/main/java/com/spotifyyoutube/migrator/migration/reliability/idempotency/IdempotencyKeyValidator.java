package com.spotifyyoutube.migrator.migration.reliability.idempotency;

/**
 * Validation utility for HTTP {@code Idempotency-Key} headers (Phase 13E).
 * <p>
 * Enforces:
 * <ul>
 *   <li>Maximum length of 255 characters</li>
 *   <li>Non-blank / non-empty values</li>
 *   <li>Disallowance of control characters (ASCII &lt; 32 or == 127) and header injection characters</li>
 * </ul>
 */
public final class IdempotencyKeyValidator {

    public static final int MAX_KEY_LENGTH = 255;

    private IdempotencyKeyValidator() {
    }

    /**
     * Validates an idempotency key string.
     *
     * @param key the raw header value
     * @return validation outcome with error message if invalid
     */
    public static ValidationResult validate(String key) {
        if (key == null) {
            return ValidationResult.invalid("Idempotency-Key header is required");
        }

        if (key.trim().isEmpty()) {
            return ValidationResult.invalid("Idempotency-Key header must not be blank");
        }

        if (key.length() > MAX_KEY_LENGTH) {
            return ValidationResult.invalid(String.format(
                    "Idempotency-Key header exceeds maximum length of %d characters (length was %d)",
                    MAX_KEY_LENGTH, key.length()
            ));
        }

        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 32 || c == 127) {
                return ValidationResult.invalid("Idempotency-Key header contains malformed or control characters");
            }
        }

        return ValidationResult.valid();
    }

    /**
     * Validation outcome.
     *
     * @param isValid      true if valid
     * @param errorMessage descriptive error message if invalid
     */
    public record ValidationResult(boolean isValid, String errorMessage) {
        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String errorMessage) {
            return new ValidationResult(false, errorMessage);
        }
    }
}
