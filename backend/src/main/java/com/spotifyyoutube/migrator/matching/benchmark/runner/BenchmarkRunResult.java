package com.spotifyyoutube.migrator.matching.benchmark.runner;

import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDatasetSplit;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkExpectedOutcome;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkTrackCategory;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable aggregated result of a benchmark evaluation run across a ground-truth dataset.
 */
public record BenchmarkRunResult(
        String datasetVersion,
        String datasetDescription,
        String datasetCreatedAt,
        int totalDatasetExamples,
        List<BenchmarkExampleResult> exampleResults,
        String candidatePoolStrategy,
        BenchmarkEligibilityPolicy eligibilityPolicy,
        long totalDurationMs
) {

    public BenchmarkRunResult {
        Objects.requireNonNull(datasetVersion, "datasetVersion must not be null");
        Objects.requireNonNull(candidatePoolStrategy, "candidatePoolStrategy must not be null");
        Objects.requireNonNull(eligibilityPolicy, "eligibilityPolicy must not be null");
        exampleResults = exampleResults != null ? List.copyOf(exampleResults) : List.of();
    }

    public int totalExamples() {
        return exampleResults.size();
    }

    public int eligibleCount() {
        return (int) exampleResults.stream()
                .filter(BenchmarkExampleResult::eligible)
                .count();
    }

    public int excludedCount() {
        return (int) exampleResults.stream()
                .filter(r -> !r.eligible() && r.exclusionReason() != EvaluationExclusionReason.EXECUTION_FAILURE)
                .count();
    }

    public int failedCount() {
        return (int) exampleResults.stream()
                .filter(r -> r.exclusionReason() == EvaluationExclusionReason.EXECUTION_FAILURE
                        || r.errorClassification() == BenchmarkErrorCategory.EXECUTION_FAILURE)
                .count();
    }

    public List<BenchmarkExampleResult> eligibleResults() {
        return exampleResults.stream()
                .filter(BenchmarkExampleResult::eligible)
                .toList();
    }

    public List<BenchmarkExampleResult> excludedResults() {
        return exampleResults.stream()
                .filter(r -> !r.eligible() && r.exclusionReason() != EvaluationExclusionReason.EXECUTION_FAILURE)
                .toList();
    }

    public List<BenchmarkExampleResult> failedResults() {
        return exampleResults.stream()
                .filter(r -> r.exclusionReason() == EvaluationExclusionReason.EXECUTION_FAILURE
                        || r.errorClassification() == BenchmarkErrorCategory.EXECUTION_FAILURE)
                .toList();
    }

    public List<BenchmarkExampleResult> eligiblePositiveMatches() {
        return exampleResults.stream()
                .filter(BenchmarkExampleResult::eligible)
                .filter(r -> r.expectedOutcome().isMatch())
                .toList();
    }

    public List<BenchmarkExampleResult> eligibleNoMatches() {
        return exampleResults.stream()
                .filter(BenchmarkExampleResult::eligible)
                .filter(r -> r.expectedOutcome().isNoMatch())
                .toList();
    }

    public List<BenchmarkExampleResult> resultsBySplit(BenchmarkDatasetSplit split) {
        Objects.requireNonNull(split, "split must not be null");
        return exampleResults.stream()
                .filter(r -> r.split() == split)
                .toList();
    }

    public List<BenchmarkExampleResult> resultsByCategory(BenchmarkTrackCategory category) {
        Objects.requireNonNull(category, "category must not be null");
        return exampleResults.stream()
                .filter(r -> r.category() == category)
                .toList();
    }

    public List<BenchmarkExampleResult> resultsByErrorCategory(BenchmarkErrorCategory category) {
        Objects.requireNonNull(category, "category must not be null");
        return exampleResults.stream()
                .filter(r -> r.errorClassification() == category)
                .toList();
    }

    public List<BenchmarkExampleResult> resultsByOutcome(BenchmarkExpectedOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        return exampleResults.stream()
                .filter(r -> r.expectedOutcome() == outcome)
                .toList();
    }

    public List<BenchmarkExampleResult> resultsByDecision(MatchDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        return exampleResults.stream()
                .filter(r -> r.predictedDecision() == decision)
                .toList();
    }

    public Optional<BenchmarkExampleResult> getExampleResult(String exampleId) {
        if (exampleId == null || exampleId.isBlank()) {
            return Optional.empty();
        }
        return exampleResults.stream()
                .filter(r -> Objects.equals(r.exampleId(), exampleId.trim()))
                .findFirst();
    }
}
