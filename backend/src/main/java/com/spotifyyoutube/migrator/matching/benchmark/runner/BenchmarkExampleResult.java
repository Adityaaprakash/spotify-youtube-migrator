package com.spotifyyoutube.migrator.matching.benchmark.runner;

import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDatasetSplit;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkExpectedOutcome;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkTrackCategory;
import com.spotifyyoutube.migrator.matching.engine.MatchConfidence;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable per-example evaluation result produced by the benchmark runner.
 * <p>
 * Captures ground truth, candidate pool presence, matching engine predictions,
 * ranked candidate diagnostics, eligibility status, and deterministic error classification.
 */
public record BenchmarkExampleResult(
        String exampleId,
        BenchmarkExpectedOutcome expectedOutcome,
        String expectedTargetVideoId,
        String predictedTargetVideoId,
        MatchDecision predictedDecision,
        MatchConfidence predictedConfidence,
        Double predictedScore,
        Double predictedProbability,
        List<RankedCandidateSummary> topRankedCandidates,
        int candidatePoolSize,
        boolean targetPresentInCandidatePool,
        boolean predictedTargetMatchesExpected,
        boolean eligible,
        EvaluationExclusionReason exclusionReason,
        BenchmarkTrackCategory category,
        BenchmarkDatasetSplit split,
        long executionDurationMs,
        BenchmarkErrorCategory errorClassification,
        String errorMessage
) {

    public BenchmarkExampleResult {
        Objects.requireNonNull(exampleId, "exampleId must not be null");
        Objects.requireNonNull(expectedOutcome, "expectedOutcome must not be null");
        Objects.requireNonNull(exclusionReason, "exclusionReason must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(split, "split must not be null");
        Objects.requireNonNull(errorClassification, "errorClassification must not be null");
        topRankedCandidates = topRankedCandidates != null ? List.copyOf(topRankedCandidates) : List.of();
    }

    /**
     * Factory for an excluded example that was not evaluated by the matching engine.
     */
    public static BenchmarkExampleResult excluded(
            String exampleId,
            BenchmarkExpectedOutcome expectedOutcome,
            String expectedTargetVideoId,
            EvaluationExclusionReason exclusionReason,
            BenchmarkTrackCategory category,
            BenchmarkDatasetSplit split,
            BenchmarkErrorCategory errorCategory
    ) {
        return new BenchmarkExampleResult(
                exampleId,
                expectedOutcome,
                expectedTargetVideoId,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                0,
                false,
                false,
                false,
                exclusionReason,
                category,
                split,
                0L,
                errorCategory,
                null
        );
    }

    /**
     * Factory for an execution failure where the matching engine threw an exception.
     */
    public static BenchmarkExampleResult failure(
            String exampleId,
            BenchmarkExpectedOutcome expectedOutcome,
            String expectedTargetVideoId,
            int candidatePoolSize,
            boolean targetPresentInCandidatePool,
            BenchmarkTrackCategory category,
            BenchmarkDatasetSplit split,
            long durationMs,
            String errorMessage
    ) {
        return new BenchmarkExampleResult(
                exampleId,
                expectedOutcome,
                expectedTargetVideoId,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                candidatePoolSize,
                targetPresentInCandidatePool,
                false,
                false,
                EvaluationExclusionReason.EXECUTION_FAILURE,
                category,
                split,
                durationMs,
                BenchmarkErrorCategory.EXECUTION_FAILURE,
                errorMessage
        );
    }

    /**
     * Indicates whether the expected target video was ranked in position 1.
     */
    public boolean isTop1Match() {
        if (!expectedOutcome.isMatch() || expectedTargetVideoId == null || topRankedCandidates.isEmpty()) {
            return false;
        }
        return Objects.equals(topRankedCandidates.get(0).videoId(), expectedTargetVideoId);
    }

    /**
     * Indicates whether the expected target video was ranked in the top 3 positions.
     */
    public boolean isTop3Match() {
        if (!expectedOutcome.isMatch() || expectedTargetVideoId == null || topRankedCandidates.isEmpty()) {
            return false;
        }
        int limit = Math.min(3, topRankedCandidates.size());
        for (int i = 0; i < limit; i++) {
            if (Objects.equals(topRankedCandidates.get(i).videoId(), expectedTargetVideoId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Indicates whether the evaluated candidate corresponds to the true expected target track,
     * regardless of whether the final decision policy auto-accepted, abstained (REVIEW_REQUIRED), or rejected.
     * <p>
     * Decouples candidate correctness from decision-policy classification.
     */
    public boolean isCandidateCorrect() {
        return expectedOutcome.isMatch() && predictedTargetMatchesExpected;
    }

    /**
     * Indicates whether the engine made a correct automatic acceptance decision (True Positive).
     */
    public boolean isCorrectAutoAccept() {
        return expectedOutcome.isMatch()
                && predictedDecision == MatchDecision.MATCH
                && predictedTargetMatchesExpected;
    }

    /**
     * Indicates whether the engine made an incorrect automatic acceptance decision (False Positive).
     */
    public boolean isIncorrectAutoAccept() {
        if (predictedDecision != MatchDecision.MATCH) {
            return false;
        }
        return expectedOutcome.isNoMatch() || !predictedTargetMatchesExpected;
    }

    /**
     * Indicates whether the engine abstained and requested human review.
     */
    public boolean isReviewRequired() {
        return predictedDecision == MatchDecision.REVIEW_REQUIRED;
    }

    /**
     * Indicates whether the engine correctly rejected a non-match track (True Negative).
     */
    public boolean isCorrectNoMatch() {
        return expectedOutcome.isNoMatch() && predictedDecision == MatchDecision.NO_MATCH;
    }

    /**
     * Indicates whether the engine incorrectly rejected a positive match track (False Negative).
     */
    public boolean isIncorrectRejection() {
        return expectedOutcome.isMatch() && predictedDecision == MatchDecision.NO_MATCH;
    }

    /**
     * Indicates a retrieval or discovery failure where the target candidate was absent from the pool.
     */
    public boolean isCandidateRecallFailure() {
        return expectedOutcome.isMatch() && !targetPresentInCandidatePool;
    }

    public Optional<String> getExpectedTargetVideoId() {
        return Optional.ofNullable(expectedTargetVideoId);
    }

    public Optional<String> getPredictedTargetVideoId() {
        return Optional.ofNullable(predictedTargetVideoId);
    }

    public Optional<MatchDecision> getPredictedDecision() {
        return Optional.ofNullable(predictedDecision);
    }

    public Optional<MatchConfidence> getPredictedConfidence() {
        return Optional.ofNullable(predictedConfidence);
    }

    public Optional<Double> getPredictedScore() {
        return Optional.ofNullable(predictedScore);
    }

    public Optional<Double> getPredictedProbability() {
        return Optional.ofNullable(predictedProbability);
    }

    public Optional<String> getErrorMessage() {
        return Optional.ofNullable(errorMessage);
    }
}
