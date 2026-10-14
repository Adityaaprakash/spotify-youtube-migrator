package com.spotifyyoutube.migrator.matching.benchmark.calibration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Optimizes decision policy thresholds on empirical benchmark observations.
 * <p>
 * Sweeps candidate thresholds across raw similarity scores and calibrated probabilities,
 * evaluates full confusion matrices, and selects optimal operating points subject to
 * target precision constraints.
 */
public class DecisionThresholdOptimizer {

    private static final double DEFAULT_SWEEP_START = 0.50;
    private static final double DEFAULT_SWEEP_END = 0.99;
    private static final double DEFAULT_SWEEP_STEP = 0.01;
    private static final double DEFAULT_TARGET_PRECISION = 0.95;

    /**
     * Executes a complete threshold optimization analysis over observations using a fitted Platt model.
     *
     * @param observations empirical observations
     * @param model fitted Platt scaling model
     * @param targetPrecision desired precision constraint (e.g. 0.95 or 0.98)
     * @param split evaluation split name
     * @return complete ThresholdOptimizationReport
     */
    public ThresholdOptimizationReport optimize(
            List<CalibrationObservation> observations,
            PlattScalingModel model,
            double targetPrecision,
            String split
    ) {
        Objects.requireNonNull(observations, "observations must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(split, "split must not be null");

        List<ThresholdEvaluationPoint> scorePoints = sweepScores(
                observations,
                DEFAULT_SWEEP_START,
                DEFAULT_SWEEP_END,
                DEFAULT_SWEEP_STEP
        );

        List<ThresholdEvaluationPoint> probabilityPoints = sweepProbabilities(
                observations,
                model,
                DEFAULT_SWEEP_START,
                DEFAULT_SWEEP_END,
                DEFAULT_SWEEP_STEP
        );

        ThresholdEvaluationPoint optimalScore = findOptimalOperatingPoint(scorePoints, targetPrecision);
        ThresholdEvaluationPoint optimalProb = findOptimalOperatingPoint(probabilityPoints, targetPrecision);

        return new ThresholdOptimizationReport(
                scorePoints,
                probabilityPoints,
                optimalScore,
                optimalProb,
                targetPrecision,
                split
        );
    }

    /**
     * Sweeps operating thresholds over raw composite similarity scores.
     *
     * @param observations empirical observations
     * @param start start threshold
     * @param end end threshold
     * @param step step increment
     * @return list of evaluated operating points
     */
    public List<ThresholdEvaluationPoint> sweepScores(
            List<CalibrationObservation> observations,
            double start,
            double end,
            double step
    ) {
        validateSweepParams(start, end, step);
        List<ThresholdEvaluationPoint> points = new ArrayList<>();

        for (double th = start; th <= end + (step / 2.0); th += step) {
            double currentTh = Math.round(th * 1000.0) / 1000.0;
            int tp = 0;
            int fp = 0;
            int tn = 0;
            int fn = 0;

            for (CalibrationObservation obs : observations) {
                boolean predictedMatch = obs.score() >= currentTh;
                if (obs.isCandidateCorrect()) {
                    if (predictedMatch) {
                        tp++;
                    } else {
                        fn++;
                    }
                } else {
                    if (predictedMatch) {
                        fp++;
                    } else {
                        tn++;
                    }
                }
            }

            points.add(ThresholdEvaluationPoint.of(currentTh, tp, fp, tn, fn));
        }

        return List.copyOf(points);
    }

    /**
     * Sweeps operating thresholds over calibrated match probabilities.
     *
     * @param observations empirical observations
     * @param model fitted Platt scaling model
     * @param start start threshold
     * @param end end threshold
     * @param step step increment
     * @return list of evaluated operating points
     */
    public List<ThresholdEvaluationPoint> sweepProbabilities(
            List<CalibrationObservation> observations,
            PlattScalingModel model,
            double start,
            double end,
            double step
    ) {
        Objects.requireNonNull(model, "model must not be null");
        validateSweepParams(start, end, step);
        List<ThresholdEvaluationPoint> points = new ArrayList<>();

        for (double th = start; th <= end + (step / 2.0); th += step) {
            double currentTh = Math.round(th * 1000.0) / 1000.0;
            int tp = 0;
            int fp = 0;
            int tn = 0;
            int fn = 0;

            for (CalibrationObservation obs : observations) {
                double prob = model.predictProbability(obs.score());
                boolean predictedMatch = prob >= currentTh;

                if (obs.isCandidateCorrect()) {
                    if (predictedMatch) {
                        tp++;
                    } else {
                        fn++;
                    }
                } else {
                    if (predictedMatch) {
                        fp++;
                    } else {
                        tn++;
                    }
                }
            }

            points.add(ThresholdEvaluationPoint.of(currentTh, tp, fp, tn, fn));
        }

        return List.copyOf(points);
    }

    /**
     * Selects the optimal operating point meeting the target precision while maximizing recall.
     *
     * @param points list of evaluated operating points, must not be empty
     * @param targetPrecision target precision requirement
     * @return optimal ThresholdEvaluationPoint
     */
    public ThresholdEvaluationPoint findOptimalOperatingPoint(
            List<ThresholdEvaluationPoint> points,
            double targetPrecision
    ) {
        Objects.requireNonNull(points, "points must not be null");
        if (points.isEmpty()) {
            throw new IllegalArgumentException("points must not be empty");
        }
        if (Double.isNaN(targetPrecision) || targetPrecision <= 0.0 || targetPrecision > 1.0) {
            throw new IllegalArgumentException("targetPrecision must be in (0.0, 1.0]: " + targetPrecision);
        }

        List<ThresholdEvaluationPoint> qualifying = points.stream()
                .filter(p -> p.precision() >= targetPrecision && p.truePositives() > 0)
                .toList();

        if (!qualifying.isEmpty()) {
            // Pick point with highest recall, tiebreak by highest F1, then lowest threshold (maximum yield)
            return qualifying.stream()
                    .max(Comparator.comparingDouble(ThresholdEvaluationPoint::recall)
                            .thenComparingDouble(ThresholdEvaluationPoint::f1Score)
                            .thenComparing(Comparator.comparingDouble(ThresholdEvaluationPoint::threshold).reversed()))
                    .orElse(qualifying.getFirst());
        }

        // If no point achieves target precision with TP > 0, select point with highest precision
        return points.stream()
                .max(Comparator.comparingDouble(ThresholdEvaluationPoint::precision)
                        .thenComparingDouble(ThresholdEvaluationPoint::f1Score))
                .orElse(points.getFirst());
    }

    private void validateSweepParams(double start, double end, double step) {
        if (Double.isNaN(start) || start < 0.0 || start > 1.0) {
            throw new IllegalArgumentException("start must be in [0.0, 1.0]: " + start);
        }
        if (Double.isNaN(end) || end < 0.0 || end > 1.0) {
            throw new IllegalArgumentException("end must be in [0.0, 1.0]: " + end);
        }
        if (start > end) {
            throw new IllegalArgumentException("start (" + start + ") > end (" + end + ")");
        }
        if (step <= 0.0 || step > (end - start)) {
            throw new IllegalArgumentException("step must be positive and <= (end - start): " + step);
        }
    }
}
