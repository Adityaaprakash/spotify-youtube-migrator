package com.spotifyyoutube.migrator.matching.benchmark.metric;

import java.util.Optional;

/**
 * Diagnostic bin in a reliability / calibration diagram.
 *
 * @param bucketIndex 0-based bucket index
 * @param lowerBound inclusive lower probability bound (e.g. 0.80)
 * @param upperBound upper probability bound (exclusive, except for last bucket which is inclusive of 1.0)
 * @param count number of evaluated predictions falling in this bin
 * @param meanConfidence average predicted probability for examples in this bin, or null if empty
 * @param accuracy proportion of examples in this bin that were true positive matches, or null if empty
 * @param isEmpty true if no examples fell into this bucket
 */
public record CalibrationBucket(
        int bucketIndex,
        double lowerBound,
        double upperBound,
        int count,
        Double meanConfidence,
        Double accuracy,
        boolean isEmpty
) {

    public Optional<Double> getMeanConfidence() {
        return Optional.ofNullable(meanConfidence);
    }

    public Optional<Double> getAccuracy() {
        return Optional.ofNullable(accuracy);
    }

    public Optional<Double> getCalibrationError() {
        if (isEmpty || meanConfidence == null || accuracy == null) {
            return Optional.empty();
        }
        return Optional.of(Math.abs(accuracy - meanConfidence));
    }
}
