package com.spotifyyoutube.migrator.matching.benchmark;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Immutable container for a ground-truth music matching benchmark dataset.
 *
 * @param version non-null semantic dataset version identifier (e.g. "1.0.0")
 * @param description non-null descriptive summary of dataset contents
 * @param createdAt non-null ISO-8601 creation date string (e.g. "2026-10-13")
 * @param examples unmodifiable list of benchmark examples
 */
public record BenchmarkDataset(
        String version,
        String description,
        String createdAt,
        List<BenchmarkExample> examples
) {

    public BenchmarkDataset {
        Objects.requireNonNull(version, "version must not be null");
        if (version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        version = version.trim();
        Objects.requireNonNull(description, "description must not be null");
        description = description.trim();
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        createdAt = createdAt.trim();
        examples = examples != null ? List.copyOf(examples) : List.of();
    }

    /**
     * Total number of examples in the dataset.
     *
     * @return example count
     */
    public int size() {
        return examples.size();
    }

    /**
     * Retrieves an example by its unique ID.
     *
     * @param id benchmark example ID
     * @return optional containing the example if present
     */
    public Optional<BenchmarkExample> getExampleById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String searchId = id.trim();
        return examples.stream()
                .filter(e -> Objects.equals(e.id(), searchId))
                .findFirst();
    }

    /**
     * Filters examples by dataset split.
     *
     * @param split target split
     * @return unmodifiable list of matching examples
     */
    public List<BenchmarkExample> getExamplesBySplit(BenchmarkDatasetSplit split) {
        Objects.requireNonNull(split, "split must not be null");
        return examples.stream()
                .filter(e -> e.split() == split)
                .toList();
    }

    /**
     * Filters examples by expected outcome.
     *
     * @param outcome expected outcome
     * @return unmodifiable list of matching examples
     */
    public List<BenchmarkExample> getExamplesByOutcome(BenchmarkExpectedOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        return examples.stream()
                .filter(e -> e.expectedOutcome() == outcome)
                .toList();
    }

    /**
     * Filters examples by human verification status.
     *
     * @param status verification status
     * @return unmodifiable list of matching examples
     */
    public List<BenchmarkExample> getExamplesByVerificationStatus(BenchmarkVerificationStatus status) {
        Objects.requireNonNull(status, "status must not be null");
        return examples.stream()
                .filter(e -> e.verificationStatus() == status)
                .toList();
    }

    /**
     * Alias for {@link #getExamplesByVerificationStatus(BenchmarkVerificationStatus)}.
     *
     * @param status verification status
     * @return unmodifiable list of matching examples
     */
    public List<BenchmarkExample> getExamplesByStatus(BenchmarkVerificationStatus status) {
        return getExamplesByVerificationStatus(status);
    }

    /**
     * Filters examples by track typology category.
     *
     * @param category track category
     * @return unmodifiable list of matching examples
     */
    public List<BenchmarkExample> getExamplesByCategory(BenchmarkTrackCategory category) {
        Objects.requireNonNull(category, "category must not be null");
        return examples.stream()
                .filter(e -> e.category() == category)
                .toList();
    }

    /**
     * Computes summary statistics and distribution telemetry for this dataset.
     *
     * @return dataset summary record
     */
    public BenchmarkDatasetSummary summary() {
        int total = examples.size();
        int verified = 0;
        int unverified = 0;
        int disputed = 0;
        int positive = 0;
        int noMatch = 0;

        Map<BenchmarkDatasetSplit, Integer> splitCounts = new EnumMap<>(BenchmarkDatasetSplit.class);
        for (BenchmarkDatasetSplit split : BenchmarkDatasetSplit.values()) {
            splitCounts.put(split, 0);
        }
        Map<BenchmarkTrackCategory, Integer> categoryCounts = new EnumMap<>(BenchmarkTrackCategory.class);
        for (BenchmarkTrackCategory category : BenchmarkTrackCategory.values()) {
            categoryCounts.put(category, 0);
        }

        for (BenchmarkExample e : examples) {
            switch (e.verificationStatus()) {
                case VERIFIED -> verified++;
                case UNVERIFIED -> unverified++;
                case DISPUTED -> disputed++;
            }
            if (e.expectedOutcome().isMatch()) {
                positive++;
            } else {
                noMatch++;
            }
            splitCounts.merge(e.split(), 1, Integer::sum);
            categoryCounts.merge(e.category(), 1, Integer::sum);
        }

        return new BenchmarkDatasetSummary(
                total,
                verified,
                unverified,
                disputed,
                positive,
                noMatch,
                splitCounts,
                categoryCounts
        );
    }
}
