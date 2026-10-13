package com.spotifyyoutube.migrator.matching.benchmark;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic validator enforcing schema contracts, referential integrity,
 * and ground-truth consistency on benchmark datasets.
 * <p>
 * Ensures that:
 * <ul>
 *   <li>Dataset version follows semantic versioning format.</li>
 *   <li>Example IDs are non-blank, formatted, and strictly unique.</li>
 *   <li>Source track metadata contains mandatory non-blank title and primary artist.</li>
 *   <li>Positive matches ({code expectedOutcome == MATCH}) require a valid target candidate.</li>
 *   <li>Legitimate non-matches ({code expectedOutcome == NO_MATCH}) forbid target candidates.</li>
 *   <li>Examples marked {code VERIFIED} contain non-blank audit verification evidence.</li>
 *   <li>Duplicate source track identifiers are flagged as warnings or errors.</li>
 * </ul>
 */
public class BenchmarkDatasetValidator {

    private static final Pattern SEMVER_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+(-[a-zA-Z0-9.]+)?$");
    private static final Pattern YOUTUBE_VIDEO_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{11}$");

    /**
     * Validates a benchmark dataset against all contract rules.
     *
     * @param dataset dataset to validate
     * @return structured validation result with errors and warnings
     */
    public BenchmarkValidationResult validate(BenchmarkDataset dataset) {
        if (dataset == null) {
            return BenchmarkValidationResult.invalid(List.of("Benchmark dataset must not be null"));
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        validateDatasetMetadata(dataset, errors);
        validateExamples(dataset.examples(), errors, warnings);

        return BenchmarkValidationResult.of(errors, warnings);
    }

    /**
     * Validates a benchmark dataset and throws {@link BenchmarkValidationException} if invalid.
     *
     * @param dataset dataset to validate
     * @throws BenchmarkValidationException if fatal errors are found
     */
    public void validateAndThrow(BenchmarkDataset dataset) {
        BenchmarkValidationResult result = validate(dataset);
        if (!result.isValid()) {
            throw new BenchmarkValidationException(
                    "Benchmark dataset validation failed with " + result.errors().size() + " error(s)",
                    result.errors()
            );
        }
    }

    private void validateDatasetMetadata(BenchmarkDataset dataset, List<String> errors) {
        if (dataset.version() == null || dataset.version().isBlank()) {
            errors.add("Dataset version must not be blank");
        } else if (!SEMVER_PATTERN.matcher(dataset.version()).matches()) {
            errors.add("Dataset version must follow semantic versioning (e.g. 1.0.0): " + dataset.version());
        }

        if (dataset.description() == null || dataset.description().isBlank()) {
            errors.add("Dataset description must not be blank");
        }

        if (dataset.createdAt() == null || dataset.createdAt().isBlank()) {
            errors.add("Dataset createdAt must not be blank");
        }
    }

    private void validateExamples(
            List<BenchmarkExample> examples,
            List<String> errors,
            List<String> warnings
    ) {
        if (examples == null || examples.isEmpty()) {
            errors.add("Dataset must contain at least one benchmark example");
            return;
        }

        Set<String> seenIds = new HashSet<>();
        Set<String> seenSourceIds = new HashSet<>();
        Set<String> seenSourceKeyPerSplit = new HashSet<>();

        for (int i = 0; i < examples.size(); i++) {
            BenchmarkExample example = examples.get(i);
            if (example == null) {
                errors.add("Benchmark example at index " + i + " must not be null");
                continue;
            }

            String id = example.id();
            if (id == null || id.isBlank()) {
                errors.add("Benchmark example at index " + i + " has a blank ID");
                continue;
            }

            // 1. Uniqueness of example ID
            if (!seenIds.add(id)) {
                errors.add("Duplicate benchmark example ID found: '" + id + "'");
            }

            // 2. Validate source track
            BenchmarkSourceTrack source = example.sourceTrack();
            if (source == null) {
                errors.add("Source track must not be null for example: " + id);
            } else {
                if (source.title() == null || source.title().isBlank()) {
                    errors.add("Source track title must not be blank for example: " + id);
                }
                if (source.primaryArtist() == null || source.primaryArtist().isBlank()) {
                    errors.add("Source track primaryArtist must not be blank for example: " + id);
                }
                if (source.durationMs() != null && source.durationMs() <= 0) {
                    errors.add("Source track durationMs must be positive for example: " + id);
                }

                // Check source ID duplicate
                if (source.id() != null && !source.id().isBlank()) {
                    String srcId = source.id().trim();
                    if (!seenSourceIds.add(srcId)) {
                        warnings.add("Duplicate source track ID '" + srcId + "' across multiple examples (e.g. "
                                + id + ")");
                    }
                }

                // Check source key collision within same split
                if (example.split() != null && source.title() != null && source.primaryArtist() != null) {
                    String splitKey = example.split().name() + "::"
                            + source.primaryArtist().toLowerCase() + "::"
                            + source.title().toLowerCase();
                    if (!seenSourceKeyPerSplit.add(splitKey)) {
                        warnings.add("Same artist and title appeared multiple times in split "
                                + example.split() + ": '" + source.primaryArtist() + " - "
                                + source.title() + "' (" + id + ")");
                    }
                }
            }

            // 3. Expected outcome vs target candidate consistency
            BenchmarkExpectedOutcome outcome = example.expectedOutcome();
            if (outcome == null) {
                errors.add("Expected outcome must not be null for example: " + id);
            } else if (outcome.isMatch()) {
                if (example.targetTrack() == null) {
                    errors.add("Positive match example must have non-null targetTrack: " + id);
                } else {
                    String videoId = example.targetTrack().videoId();
                    if (videoId == null || videoId.isBlank()) {
                        errors.add("Target YouTube videoId must not be blank for positive match: " + id);
                    } else if (!YOUTUBE_VIDEO_ID_PATTERN.matcher(videoId).matches()) {
                        warnings.add("Target videoId '" + videoId + "' does not match standard 11-char pattern for: "
                                + id);
                    }
                }
            } else if (outcome.isNoMatch()) {
                if (example.targetTrack() != null) {
                    errors.add("Legitimate no-match example must not carry targetTrack: " + id);
                }
            }

            // 4. Verification status & audit evidence
            BenchmarkVerificationStatus status = example.verificationStatus();
            if (status == null) {
                errors.add("Verification status must not be null for example: " + id);
            } else if (status.isVerified()) {
                if (example.verificationEvidence() == null || example.verificationEvidence().isBlank()) {
                    errors.add("VERIFIED example must have non-blank verificationEvidence: " + id);
                } else if (example.verificationEvidence().trim().length() < 10) {
                    errors.add("VERIFIED example must have meaningful verificationEvidence (at least 10 chars): "
                            + id);
                }
            }

            // 5. Split and category
            if (example.split() == null) {
                errors.add("Dataset split must not be null for example: " + id);
            }
            if (example.category() == null) {
                errors.add("Track category must not be null for example: " + id);
            }
        }
    }
}
