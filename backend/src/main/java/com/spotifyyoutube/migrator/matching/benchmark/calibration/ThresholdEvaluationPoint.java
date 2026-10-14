package com.spotifyyoutube.migrator.matching.benchmark.calibration;

/**
 * Immutable evaluation point representing classification metrics at a specific operating threshold.
 *
 * @param threshold evaluation threshold value in {@code [0.0, 1.0]}
 * @param totalSamples total evaluated samples
 * @param truePositives count of correctly accepted matches ($y=1, \hat{y}=1$)
 * @param falsePositives count of incorrectly accepted matches ($y=0, \hat{y}=1$)
 * @param trueNegatives count of correctly rejected non-matches ($y=0, \hat{y}=0$)
 * @param falseNegatives count of incorrectly rejected matches ($y=1, \hat{y}=0$)
 * @param precision positive predictive value ($\frac{TP}{TP + FP}$)
 * @param recall sensitivity / true positive rate ($\frac{TP}{TP + FN}$)
 * @param f1Score harmonic mean of precision and recall
 * @param falsePositiveRate fallout ($\frac{FP}{FP + TN}$)
 * @param autoMatchRate fraction of total samples accepted ($\frac{TP + FP}{N}$)
 * @param rejectionRate fraction of total samples rejected ($\frac{TN + FN}{N}$)
 */
public record ThresholdEvaluationPoint(
        double threshold,
        int totalSamples,
        int truePositives,
        int falsePositives,
        int trueNegatives,
        int falseNegatives,
        double precision,
        double recall,
        double f1Score,
        double falsePositiveRate,
        double autoMatchRate,
        double rejectionRate
) {

    public ThresholdEvaluationPoint {
        if (Double.isNaN(threshold) || Double.isInfinite(threshold) || threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be in [0.0, 1.0]: " + threshold);
        }
        if (totalSamples < 0 || truePositives < 0 || falsePositives < 0 || trueNegatives < 0 || falseNegatives < 0) {
            throw new IllegalArgumentException("Sample counts must be non-negative");
        }
        if (totalSamples != truePositives + falsePositives + trueNegatives + falseNegatives) {
            throw new IllegalArgumentException("totalSamples does not match sum of confusion matrix");
        }
    }

    /**
     * Factory method computing derived rates from confusion matrix counts.
     *
     * @param threshold operating threshold
     * @param tp true positives
     * @param fp false positives
     * @param tn true negatives
     * @param fn false negatives
     * @return constructed ThresholdEvaluationPoint
     */
    public static ThresholdEvaluationPoint of(double threshold, int tp, int fp, int tn, int fn) {
        int total = tp + fp + tn + fn;
        double precision = (tp + fp) > 0 ? ((double) tp) / (tp + fp) : 1.0;
        double recall = (tp + fn) > 0 ? ((double) tp) / (tp + fn) : 0.0;
        double f1 = (precision + recall) > 0.0 ? (2.0 * precision * recall) / (precision + recall) : 0.0;
        double fpr = (fp + tn) > 0 ? ((double) fp) / (fp + tn) : 0.0;
        double matchRate = total > 0 ? ((double) (tp + fp)) / total : 0.0;
        double rejectRate = total > 0 ? ((double) (tn + fn)) / total : 0.0;

        return new ThresholdEvaluationPoint(
                threshold,
                total,
                tp,
                fp,
                tn,
                fn,
                precision,
                recall,
                f1,
                fpr,
                matchRate,
                rejectRate
        );
    }
}
