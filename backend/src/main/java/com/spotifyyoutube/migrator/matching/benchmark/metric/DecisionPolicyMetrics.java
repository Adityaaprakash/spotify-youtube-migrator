package com.spotifyyoutube.migrator.matching.benchmark.metric;

import java.util.Optional;

/**
 * Immutable metrics evaluating the matching engine's 3-way decision policy
 * ({@code MATCH}, {@code REVIEW_REQUIRED}, {@code NO_MATCH}) against ground-truth labels.
 * <p>
 * <b>Treatment of Abstention:</b> {@code REVIEW_REQUIRED} is explicitly distinguished from
 * automated acceptance and rejection, ensuring human review cannot artificially inflate precision
 * or mask false rejections.
 *
 * @param totalEligible total number of eligible examples evaluated
 * @param totalExpectedMatch ground-truth positive match count
 * @param totalExpectedNoMatch ground-truth legitimate no-match count
 * @param correctAutoAccept count of correctly accepted positive matches (True Positives)
 * @param incorrectAutoAccept count of incorrectly accepted matches (False Positives)
 * @param falseAcceptanceOfNoMatch count of legitimate non-matches accepted as matches
 * @param wrongCandidateAccepted count of positive matches where the wrong candidate was accepted
 * @param reviewRequiredPositive count of positive matches routed to review (abstention on match)
 * @param reviewRequiredNoMatch count of non-matches routed to review (abstention on non-match)
 * @param correctNoMatch count of correctly identified non-matches (True Negatives)
 * @param incorrectRejection count of positive matches incorrectly rejected as non-matches (False Negatives)
 * @param precision precision of automated match decisions: correctAutoAccept / totalAutoAccepted
 * @param recall recall of positive matches via automated decisions: correctAutoAccept / totalExpectedMatch
 * @param falsePositiveRate false positive rate: falseAcceptanceOfNoMatch / totalExpectedNoMatch
 * @param falseNegativeRate false negative rate: incorrectRejection / totalExpectedMatch
 * @param noMatchPrecision precision of NO_MATCH rejections: correctNoMatch / totalNoMatchPredicted
 * @param noMatchRecall recall of legitimate non-matches: correctNoMatch / totalExpectedNoMatch
 * @param reviewRequiredRate proportion of tracks routed to human review: totalReviews / totalEligible
 * @param automaticAcceptanceRate proportion of tracks automatically accepted: totalAutoAccepted / totalEligible
 * @param overallAccuracy proportion of correct definitive decisions: (correctAutoAccept + correctNoMatch) / totalEligible
 */
public record DecisionPolicyMetrics(
        int totalEligible,
        int totalExpectedMatch,
        int totalExpectedNoMatch,
        int correctAutoAccept,
        int incorrectAutoAccept,
        int falseAcceptanceOfNoMatch,
        int wrongCandidateAccepted,
        int reviewRequiredPositive,
        int reviewRequiredNoMatch,
        int correctNoMatch,
        int incorrectRejection,
        Double precision,
        Double recall,
        Double falsePositiveRate,
        Double falseNegativeRate,
        Double noMatchPrecision,
        Double noMatchRecall,
        Double reviewRequiredRate,
        Double automaticAcceptanceRate,
        Double overallAccuracy
) {

    public int totalAutoAccepted() {
        return correctAutoAccept + incorrectAutoAccept;
    }

    public int totalReviewRequired() {
        return reviewRequiredPositive + reviewRequiredNoMatch;
    }

    public int totalNoMatchPredicted() {
        return correctNoMatch + incorrectRejection;
    }

    public boolean hasEvaluableData() {
        return totalEligible > 0;
    }

    public Optional<Double> getPrecision() {
        return Optional.ofNullable(precision);
    }

    public Optional<Double> getRecall() {
        return Optional.ofNullable(recall);
    }

    public Optional<Double> getFalsePositiveRate() {
        return Optional.ofNullable(falsePositiveRate);
    }

    public Optional<Double> getFalseNegativeRate() {
        return Optional.ofNullable(falseNegativeRate);
    }

    public Optional<Double> getNoMatchPrecision() {
        return Optional.ofNullable(noMatchPrecision);
    }

    public Optional<Double> getNoMatchRecall() {
        return Optional.ofNullable(noMatchRecall);
    }

    public Optional<Double> getReviewRequiredRate() {
        return Optional.ofNullable(reviewRequiredRate);
    }

    public Optional<Double> getAutomaticAcceptanceRate() {
        return Optional.ofNullable(automaticAcceptanceRate);
    }

    public Optional<Double> getOverallAccuracy() {
        return Optional.ofNullable(overallAccuracy);
    }

    public Optional<Double> getPrecisionPercent() {
        return Optional.ofNullable(precision).map(v -> Math.round(v * 10000.0) / 100.0);
    }

    public Optional<Double> getRecallPercent() {
        return Optional.ofNullable(recall).map(v -> Math.round(v * 10000.0) / 100.0);
    }

    public Optional<Double> getFalsePositiveRatePercent() {
        return Optional.ofNullable(falsePositiveRate).map(v -> Math.round(v * 10000.0) / 100.0);
    }

    public Optional<Double> getReviewRequiredRatePercent() {
        return Optional.ofNullable(reviewRequiredRate).map(v -> Math.round(v * 10000.0) / 100.0);
    }

    public Optional<Double> getAutomaticAcceptanceRatePercent() {
        return Optional.ofNullable(automaticAcceptanceRate).map(v -> Math.round(v * 10000.0) / 100.0);
    }
}
