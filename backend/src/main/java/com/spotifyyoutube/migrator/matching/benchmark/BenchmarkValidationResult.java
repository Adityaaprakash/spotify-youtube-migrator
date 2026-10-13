package com.spotifyyoutube.migrator.matching.benchmark;

import java.util.List;

/**
 * Result of validating a benchmark dataset or individual benchmark examples.
 *
 * @param isValid true if no validation errors were found
 * @param errors unmodifiable list of fatal validation error messages
 * @param warnings unmodifiable list of non-fatal warning messages
 */
public record BenchmarkValidationResult(
        boolean isValid,
        List<String> errors,
        List<String> warnings
) {

    public BenchmarkValidationResult {
        errors = errors != null ? List.copyOf(errors) : List.of();
        warnings = warnings != null ? List.copyOf(warnings) : List.of();
    }

    /**
     * Factory for a successful validation result with no warnings.
     *
     * @return valid result
     */
    public static BenchmarkValidationResult valid() {
        return new BenchmarkValidationResult(true, List.of(), List.of());
    }

    /**
     * Factory for a successful validation result with advisory warnings.
     *
     * @param warnings non-fatal warnings
     * @return valid result with warnings
     */
    public static BenchmarkValidationResult validWithWarnings(List<String> warnings) {
        return new BenchmarkValidationResult(true, List.of(), warnings);
    }

    /**
     * Factory for a failed validation result.
     *
     * @param errors validation error messages
     * @return invalid result
     */
    public static BenchmarkValidationResult invalid(List<String> errors) {
        return new BenchmarkValidationResult(false, errors, List.of());
    }

    /**
     * Factory for a validation result with both errors and warnings.
     *
     * @param errors validation error messages
     * @param warnings validation warning messages
     * @return validation result
     */
    public static BenchmarkValidationResult of(List<String> errors, List<String> warnings) {
        boolean valid = errors == null || errors.isEmpty();
        return new BenchmarkValidationResult(valid, errors, warnings);
    }

    /**
     * Returns whether there are any warnings.
     *
     * @return true if warnings exist
     */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}
