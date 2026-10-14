package com.spotifyyoutube.migrator.matching.benchmark.calibration;

import com.spotifyyoutube.migrator.matching.benchmark.metric.BenchmarkMetrics;
import com.spotifyyoutube.migrator.matching.benchmark.metric.CalibrationBucket;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkRunResult;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable report summarizing the Phase 8E empirical probability calibration and threshold optimization pass.
 *
 * @param fittedModel fitted Platt scaling model derived from the VALIDATION split
 * @param thresholdOptimizationReport threshold optimization results over score and probability domains
 * @param validationBaselineRun baseline benchmark execution results on VALIDATION split
 * @param testBaselineRun baseline uncalibrated benchmark execution results on held-out TEST split
 * @param testCalibratedRun calibrated benchmark execution results on held-out TEST split
 * @param testBaselineMetrics evaluation metrics for baseline TEST run
 * @param testCalibratedMetrics evaluation metrics for calibrated TEST run
 * @param totalDurationMs total duration of pipeline execution in milliseconds
 */
public record BenchmarkCalibrationReport(
        PlattScalingModel fittedModel,
        ThresholdOptimizationReport thresholdOptimizationReport,
        BenchmarkRunResult validationBaselineRun,
        BenchmarkRunResult testBaselineRun,
        BenchmarkRunResult testCalibratedRun,
        BenchmarkMetrics testBaselineMetrics,
        BenchmarkMetrics testCalibratedMetrics,
        long totalDurationMs
) {

    public BenchmarkCalibrationReport {
        Objects.requireNonNull(fittedModel, "fittedModel must not be null");
        Objects.requireNonNull(thresholdOptimizationReport, "thresholdOptimizationReport must not be null");
        Objects.requireNonNull(validationBaselineRun, "validationBaselineRun must not be null");
        Objects.requireNonNull(testBaselineRun, "testBaselineRun must not be null");
        Objects.requireNonNull(testCalibratedRun, "testCalibratedRun must not be null");
        Objects.requireNonNull(testBaselineMetrics, "testBaselineMetrics must not be null");
        Objects.requireNonNull(testCalibratedMetrics, "testCalibratedMetrics must not be null");
        if (totalDurationMs < 0) {
            throw new IllegalArgumentException("totalDurationMs must be non-negative: " + totalDurationMs);
        }
    }

    /**
     * Formats this calibration report into a complete GitHub Flavored Markdown document.
     *
     * @return markdown formatted report
     */
    public String formatToMarkdown() {
        StringBuilder sb = new StringBuilder();

        sb.append("# Phase 8E: Empirical Probability Calibration & Threshold Optimization Report\n\n");
        sb.append(String.format(Locale.ROOT, "- **Model Version:** `%s`\n", fittedModel.modelVersion()));
        sb.append(String.format(Locale.ROOT, "- **Training Split:** `%s` ($N = %d$, Pos = %d, Neg = %d)\n",
                fittedModel.trainingSplit(), fittedModel.sampleCount(),
                fittedModel.positiveCount(), fittedModel.negativeCount()));
        sb.append(String.format(Locale.ROOT, "- **Evaluation Split:** `TEST` ($N = %d$)\n",
                testCalibratedRun.totalDatasetExamples()));
        sb.append(String.format(Locale.ROOT, "- **Pipeline Execution Time:** %d ms\n\n", totalDurationMs));

        // 1. Model Parameters
        sb.append("## 1. Fitted Platt Scaling Model Parameters\n\n");
        sb.append("Empirical logistic transformation: $$P(\\text{match} \\mid s) = \\frac{1}{1 + \\exp(-(A \\cdot s + B))}$$\n\n");
        sb.append("| Parameter | Value | Interpretation |\n");
        sb.append("| :--- | :--- | :--- |\n");
        sb.append(String.format(Locale.ROOT, "| Slope ($A$) | `%.6f` | Monotonicity ($A > 0$) strictly preserved |\n",
                fittedModel.slopeA()));
        sb.append(String.format(Locale.ROOT, "| Intercept ($B$) | `%.6f` | Negative base log-odds shift |\n",
                fittedModel.interceptB()));
        sb.append(String.format(Locale.ROOT, "| Training Log-Likelihood | `%.4f` | Maximized via regularized Newton-Raphson |\n",
                fittedModel.logLikelihood()));
        sb.append(String.format(Locale.ROOT, "| Validation Brier Score | `%.4f` | Baseline: `%.4f` (Reduction: `%.2f%%`) |\n\n",
                fittedModel.brierScore(), fittedModel.baselineBrierScore(),
                fittedModel.brierImprovementPercentage()));

        // 2. Threshold Optimization
        sb.append("## 2. Decision Threshold Optimization\n\n");
        ThresholdEvaluationPoint optScore = thresholdOptimizationReport.optimalScorePoint();
        ThresholdEvaluationPoint optProb = thresholdOptimizationReport.optimalProbabilityPoint();

        sb.append(String.format(Locale.ROOT, "Target Precision: **%.1f%%**\n\n",
                thresholdOptimizationReport.targetPrecision() * 100.0));
        sb.append("| Domain | Optimal Threshold | Precision | Recall | F1 Score | FPR | Auto-Match Rate |\n");
        sb.append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- |\n");
        sb.append(String.format(Locale.ROOT, "| Raw Score ($s$) | `%.2f` | %.2f%% | %.2f%% | %.4f | %.4f | %.2f%% |\n",
                optScore.threshold(), optScore.precision() * 100.0, optScore.recall() * 100.0,
                optScore.f1Score(), optScore.falsePositiveRate(), optScore.autoMatchRate() * 100.0));
        sb.append(String.format(Locale.ROOT, "| Calibrated Probability ($P$) | `%.2f` | %.2f%% | %.2f%% | %.4f | %.4f | %.2f%% |\n\n",
                optProb.threshold(), optProb.precision() * 100.0, optProb.recall() * 100.0,
                optProb.f1Score(), optProb.falsePositiveRate(), optProb.autoMatchRate() * 100.0));

        // 3. Held-out TEST Split Generalization
        sb.append("## 3. Held-Out TEST Split Calibration & Verification\n\n");
        var cal = testCalibratedMetrics.calibration();
        var highConf = testCalibratedMetrics.highConfidence();

        if (cal.available()) {
            sb.append("| Calibration Metric | Baseline (Raw Score) | Calibrated (Platt) | Status |\n");
            sb.append("| :--- | :--- | :--- | :--- |\n");
            sb.append(String.format(Locale.ROOT, "| Brier Score | `%.4f` | `%.4f` | %s |\n",
                    cal.baselineBrierScore(), cal.brierScore(),
                    cal.brierScore() < cal.baselineBrierScore() ? "IMPROVED" : "NEUTRAL"));
            sb.append(String.format(Locale.ROOT, "| Expected Calibration Error (ECE) | — | `%.4f` | Evaluated |\n\n",
                    cal.expectedCalibrationError()));

            sb.append("### Reliability Diagram (TEST Split)\n\n");
            sb.append("| Probability Bin | Count | Mean Confidence | Observed Empirical Accuracy |\n");
            sb.append("| :--- | :--- | :--- | :--- |\n");
            for (CalibrationBucket bucket : cal.buckets()) {
                if (bucket.isEmpty()) {
                    sb.append(String.format(Locale.ROOT, "| [%.2f, %.2f) | 0 | — | — |\n",
                            bucket.lowerBound(), bucket.upperBound()));
                } else {
                    double meanConf = bucket.meanConfidence() != null ? bucket.meanConfidence() : 0.0;
                    double acc = bucket.accuracy() != null ? bucket.accuracy() : 0.0;
                    sb.append(String.format(Locale.ROOT, "| [%.2f, %.2f) | %d | %.4f | %.4f |\n",
                            bucket.lowerBound(), bucket.upperBound(), bucket.count(),
                            meanConf, acc));
                }
            }
            sb.append("\n");
        }

        // 4. High Confidence Verification
        sb.append("### High-Confidence Safety Audit (TEST Split)\n\n");
        if (highConf.available()) {
            int accepted = highConf.highConfidenceAcceptedCount();
            int incorrect = highConf.highConfidenceIncorrectCount();
            sb.append(String.format(Locale.ROOT, "- **High-Confidence Threshold:** `%.2f`\n", highConf.threshold()));
            sb.append(String.format(Locale.ROOT, "- **Accepted as High-Confidence:** %d\n", accepted));
            sb.append(String.format(Locale.ROOT, "- **Incorrect Matches at High Confidence:** %d (Target: 0)\n", incorrect));
            sb.append(String.format(Locale.ROOT, "- **High-Confidence Error Rate:** %s%%\n",
                    highConf.getErrorRatePercent().map(Object::toString).orElse("0.0")));

            if (accepted == 0) {
                sb.append("> [!NOTE]\n");
                sb.append("> Zero high-confidence errors were observed, but zero high-confidence predictions were accepted at this threshold. This absence of predictions must not be construed as empirical evidence of algorithmic safety.\n\n");
            } else {
                sb.append("\n");
            }
        } else {
            sb.append("> [!WARNING]\n");
            sb.append("> High confidence metrics unavailable: ").append(highConf.unavailabilityReason()).append("\n\n");
        }

        // 5. Scientific Limitations & Dataset Scope
        sb.append("## 4. Scientific Limitations & Data Provenance\n\n");
        sb.append(String.format(Locale.ROOT,
                "1. **Sample Size Scope:** Fitting was conducted on $N = %d$ observations from the `VALIDATION` partition, and generalization evaluated on $N = %d$ tracks in `TEST`. Findings reflect small-sample empirical estimations.\n",
                fittedModel.sampleCount(),
                testCalibratedRun.totalDatasetExamples()));
        sb.append("2. **Candidate Pool Provenance:** Candidate pools were generated using deterministic synthetic distractors (`ControlledDecoyCandidatePoolProvider`, `hasRealisticPool = false`). Live YouTube search ranking accuracy cannot be inferred from synthetic pool results alone.\n");
        sb.append("3. **No Production Guarantee:** Operating points meeting target precision on these partitions represent empirical observations on controlled fixtures and do not constitute absolute guarantees of 95% or 98% precision under live production distributions.\n");

        return sb.toString();
    }
}
