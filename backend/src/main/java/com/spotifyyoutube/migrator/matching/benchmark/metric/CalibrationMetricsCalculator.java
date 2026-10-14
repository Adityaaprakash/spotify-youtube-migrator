package com.spotifyyoutube.migrator.matching.benchmark.metric;

import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkExampleResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Calculator for probability calibration metrics (Brier score, ECE, reliability bins).
 */
public class CalibrationMetricsCalculator {

    public static final int DEFAULT_NUM_BUCKETS = 10;

    /**
     * Calculates calibration metrics using default 10 reliability bins.
     *
     * @param results list of per-example benchmark results
     * @return computed calibration metrics
     */
    public CalibrationMetrics calculate(List<BenchmarkExampleResult> results) {
        return calculate(results, DEFAULT_NUM_BUCKETS);
    }

    /**
     * Calculates calibration metrics with a customizable number of bins.
     *
     * @param results list of per-example benchmark results
     * @param numBuckets number of reliability diagram histogram bins (must be >= 2)
     * @return computed calibration metrics
     */
    public CalibrationMetrics calculate(List<BenchmarkExampleResult> results, int numBuckets) {
        if (numBuckets < 2) {
            throw new IllegalArgumentException("numBuckets must be at least 2: " + numBuckets);
        }

        if (results == null || results.isEmpty()) {
            return CalibrationMetrics.unavailable("No benchmark results provided");
        }

        List<BenchmarkExampleResult> evaluable = results.stream()
                .filter(BenchmarkExampleResult::eligible)
                .filter(r -> r.predictedProbability() != null)
                .toList();

        if (evaluable.isEmpty()) {
            return CalibrationMetrics.unavailable(
                    "No calibrated probabilities exposed by matching engine (calibratedProbability is null; "
                            + "DefaultMatchProbabilityCalibrator is uncalibrated baseline). "
                            + "Empirical probability evaluation requires an active, validated calibration model."
            );
        }

        int n = evaluable.size();
        double squaredErrorSum = 0.0;
        double positiveCount = 0.0;

        double sumConfidenceCorrect = 0.0;
        int countCorrect = 0;
        double sumConfidenceIncorrect = 0.0;
        int countIncorrect = 0;

        // Initialize bins
        int[] binCounts = new int[numBuckets];
        double[] binProbSums = new double[numBuckets];
        double[] binLabelSums = new double[numBuckets];

        double binWidth = 1.0 / numBuckets;

        for (BenchmarkExampleResult r : evaluable) {
            double p = r.predictedProbability();
            // Ground-truth binary label for candidate correctness:
            // y = 1.0 if evaluated candidate is the true expected target, 0.0 otherwise.
            // Explicitly decoupled from decision policy abstention (REVIEW_REQUIRED).
            double y = r.isCandidateCorrect() ? 1.0 : 0.0;

            squaredErrorSum += Math.pow(p - y, 2);
            if (y == 1.0) {
                positiveCount += 1.0;
                sumConfidenceCorrect += p;
                countCorrect++;
            } else {
                sumConfidenceIncorrect += p;
                countIncorrect++;
            }

            // Assign to bucket
            int bucketIndex = (p >= 1.0) ? numBuckets - 1 : (int) (p * numBuckets);
            bucketIndex = Math.min(bucketIndex, numBuckets - 1);

            binCounts[bucketIndex]++;
            binProbSums[bucketIndex] += p;
            binLabelSums[bucketIndex] += y;
        }

        double brierScore = squaredErrorSum / n;
        double baseRate = positiveCount / n;
        double baselineBrierScore = baseRate * (1.0 - baseRate);

        // Build buckets and compute ECE
        List<CalibrationBucket> buckets = new ArrayList<>(numBuckets);
        double eceSum = 0.0;

        for (int i = 0; i < numBuckets; i++) {
            double lower = round4(i * binWidth);
            double upper = round4((i + 1) * binWidth);
            int count = binCounts[i];

            if (count > 0) {
                double meanConf = binProbSums[i] / count;
                double acc = binLabelSums[i] / count;
                eceSum += ((double) count / n) * Math.abs(acc - meanConf);

                buckets.add(new CalibrationBucket(
                        i,
                        lower,
                        upper,
                        count,
                        round4(meanConf),
                        round4(acc),
                        false
                ));
            } else {
                buckets.add(new CalibrationBucket(
                        i,
                        lower,
                        upper,
                        0,
                        null,
                        null,
                        true
                ));
            }
        }

        Double meanConfCorrect = countCorrect > 0 ? round4(sumConfidenceCorrect / countCorrect) : null;
        Double meanConfIncorrect = countIncorrect > 0 ? round4(sumConfidenceIncorrect / countIncorrect) : null;

        return new CalibrationMetrics(
                true,
                null,
                CalibrationMetrics.DEFAULT_TARGET_DEFINITION,
                n,
                round4(brierScore),
                round4(baselineBrierScore),
                round4(eceSum),
                buckets,
                meanConfCorrect,
                meanConfIncorrect
        );
    }

    private Double round4(Double value) {
        if (value == null) {
            return null;
        }
        return Math.round(value * 10000.0) / 10000.0;
    }
}
