package com.spotifyyoutube.migrator.matching.benchmark.metric;

import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkExampleResult;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;

import java.util.List;

/**
 * Calculator for matching decision policy metrics (Precision, Recall, FPR, FNR, Review Rate, Auto-Accept Rate).
 */
public class DecisionPolicyMetricsCalculator {

    /**
     * Calculates decision policy metrics across eligible benchmark results.
     *
     * @param results list of per-example benchmark results
     * @return computed decision policy metrics
     */
    public DecisionPolicyMetrics calculate(List<BenchmarkExampleResult> results) {
        if (results == null || results.isEmpty()) {
            return empty();
        }

        List<BenchmarkExampleResult> eligible = results.stream()
                .filter(BenchmarkExampleResult::eligible)
                .toList();

        int totalEligible = eligible.size();
        if (totalEligible == 0) {
            return empty();
        }

        int totalExpectedMatch = 0;
        int totalExpectedNoMatch = 0;

        int correctAutoAccept = 0;
        int falseAcceptanceOfNoMatch = 0;
        int wrongCandidateAccepted = 0;
        int reviewRequiredPositive = 0;
        int reviewRequiredNoMatch = 0;
        int correctNoMatch = 0;
        int incorrectRejection = 0;

        for (BenchmarkExampleResult r : eligible) {
            boolean isMatchGroundTruth = r.expectedOutcome().isMatch();
            if (isMatchGroundTruth) {
                totalExpectedMatch++;
            } else {
                totalExpectedNoMatch++;
            }

            MatchDecision decision = r.predictedDecision();
            if (decision == MatchDecision.MATCH) {
                if (isMatchGroundTruth) {
                    if (r.predictedTargetMatchesExpected()) {
                        correctAutoAccept++;
                    } else {
                        wrongCandidateAccepted++;
                    }
                } else {
                    falseAcceptanceOfNoMatch++;
                }
            } else if (decision == MatchDecision.REVIEW_REQUIRED) {
                if (isMatchGroundTruth) {
                    reviewRequiredPositive++;
                } else {
                    reviewRequiredNoMatch++;
                }
            } else if (decision == MatchDecision.NO_MATCH) {
                if (isMatchGroundTruth) {
                    incorrectRejection++;
                } else {
                    correctNoMatch++;
                }
            }
        }

        int incorrectAutoAccept = falseAcceptanceOfNoMatch + wrongCandidateAccepted;
        int totalAutoAccepted = correctAutoAccept + incorrectAutoAccept;
        int totalNoMatchPredicted = correctNoMatch + incorrectRejection;
        int totalReviews = reviewRequiredPositive + reviewRequiredNoMatch;

        Double precision = totalAutoAccepted > 0
                ? (double) correctAutoAccept / totalAutoAccepted
                : null;

        Double recall = totalExpectedMatch > 0
                ? (double) correctAutoAccept / totalExpectedMatch
                : null;

        Double fpr = totalExpectedNoMatch > 0
                ? (double) falseAcceptanceOfNoMatch / totalExpectedNoMatch
                : null;

        Double fnr = totalExpectedMatch > 0
                ? (double) incorrectRejection / totalExpectedMatch
                : null;

        Double noMatchPrecision = totalNoMatchPredicted > 0
                ? (double) correctNoMatch / totalNoMatchPredicted
                : null;

        Double noMatchRecall = totalExpectedNoMatch > 0
                ? (double) correctNoMatch / totalExpectedNoMatch
                : null;

        Double reviewRate = (double) totalReviews / totalEligible;
        Double autoAcceptRate = (double) totalAutoAccepted / totalEligible;
        Double overallAccuracy = (double) (correctAutoAccept + correctNoMatch) / totalEligible;

        return new DecisionPolicyMetrics(
                totalEligible,
                totalExpectedMatch,
                totalExpectedNoMatch,
                correctAutoAccept,
                incorrectAutoAccept,
                falseAcceptanceOfNoMatch,
                wrongCandidateAccepted,
                reviewRequiredPositive,
                reviewRequiredNoMatch,
                correctNoMatch,
                incorrectRejection,
                round4(precision),
                round4(recall),
                round4(fpr),
                round4(fnr),
                round4(noMatchPrecision),
                round4(noMatchRecall),
                round4(reviewRate),
                round4(autoAcceptRate),
                round4(overallAccuracy)
        );
    }

    private DecisionPolicyMetrics empty() {
        return new DecisionPolicyMetrics(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                null, null, null, null, null, null, null, null, null
        );
    }

    private Double round4(Double value) {
        if (value == null) {
            return null;
        }
        return Math.round(value * 10000.0) / 10000.0;
    }
}
