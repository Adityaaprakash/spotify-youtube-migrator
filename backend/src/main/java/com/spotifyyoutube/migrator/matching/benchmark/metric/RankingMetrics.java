package com.spotifyyoutube.migrator.matching.benchmark.metric;

import java.util.Optional;

/**
 * Immutable evaluation metrics capturing candidate ranking quality (Top-1 and Top-3 accuracy).
 * <p>
 * Evaluated strictly on eligible positive {@code MATCH} examples. Distinguishes ranking performance
 * across all positive examples from performance on the candidate-present subset to isolate candidate
 * discovery recall failures from downstream ranking errors.
 *
 * @param eligiblePositiveMatches total number of eligible positive match examples evaluated
 * @param targetPresentCount count of examples where the expected target was present in the candidate pool
 * @param targetAbsentCount count of examples where the expected target was absent (retrieval / recall failure)
 * @param top1MatchCount count of examples whose expected target was ranked first
 * @param top3MatchCount count of examples whose expected target appeared in the top 3 ranked candidates
 * @param top1AccuracyAll proportion of all eligible positive examples whose target was ranked first
 * @param top1AccuracyPresent proportion of candidate-present positive examples whose target was ranked first
 * @param top3AccuracyAll proportion of all eligible positive examples whose target was in the top 3
 * @param top3AccuracyPresent proportion of candidate-present positive examples whose target was in the top 3
 * @param candidateRecallRate proportion of eligible positive examples whose target was retrieved into the pool
 */
public record RankingMetrics(
        int eligiblePositiveMatches,
        int targetPresentCount,
        int targetAbsentCount,
        int top1MatchCount,
        int top3MatchCount,
        Double top1AccuracyAll,
        Double top1AccuracyPresent,
        Double top3AccuracyAll,
        Double top3AccuracyPresent,
        Double candidateRecallRate
) {

    public boolean hasEvaluableData() {
        return eligiblePositiveMatches > 0;
    }

    public Optional<Double> getTop1AccuracyAll() {
        return Optional.ofNullable(top1AccuracyAll);
    }

    public Optional<Double> getTop1AccuracyPresent() {
        return Optional.ofNullable(top1AccuracyPresent);
    }

    public Optional<Double> getTop3AccuracyAll() {
        return Optional.ofNullable(top3AccuracyAll);
    }

    public Optional<Double> getTop3AccuracyPresent() {
        return Optional.ofNullable(top3AccuracyPresent);
    }

    public Optional<Double> getCandidateRecallRate() {
        return Optional.ofNullable(candidateRecallRate);
    }

    public Optional<Double> getTop1AccuracyAllPercent() {
        return Optional.ofNullable(top1AccuracyAll).map(v -> Math.round(v * 10000.0) / 100.0);
    }

    public Optional<Double> getTop1AccuracyPresentPercent() {
        return Optional.ofNullable(top1AccuracyPresent).map(v -> Math.round(v * 10000.0) / 100.0);
    }

    public Optional<Double> getTop3AccuracyAllPercent() {
        return Optional.ofNullable(top3AccuracyAll).map(v -> Math.round(v * 10000.0) / 100.0);
    }

    public Optional<Double> getTop3AccuracyPresentPercent() {
        return Optional.ofNullable(top3AccuracyPresent).map(v -> Math.round(v * 10000.0) / 100.0);
    }

    public Optional<Double> getCandidateRecallRatePercent() {
        return Optional.ofNullable(candidateRecallRate).map(v -> Math.round(v * 10000.0) / 100.0);
    }
}
