package com.spotifyyoutube.migrator.matching.benchmark.calibration;

import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkExampleResult;

import java.util.List;
import java.util.Objects;

/**
 * Fits Platt scaling logistic calibration parameters $(A, B)$ to empirical benchmark observations.
 * <p>
 * Optimizes the model:
 * $$P(\text{match} \mid s) = \frac{1}{1 + \exp(-(A \cdot s + B))}$$
 * using Newton-Raphson optimization with Levenberg-Marquardt damping and backtracking line search.
 * <p>
 * Implements Platt's regularized target probabilities (Platt, 1999) to prevent infinite log-odds
 * and overfitting when scores are linearly separable:
 * $$t_+ = \frac{N_+ + 1}{N_+ + 2}, \quad t_- = \frac{1}{N_- + 2}$$
 * <p>
 * Guarantees the monotonicity invariant $A > 0$ so that higher similarity scores strictly map
 * to equal or higher match probabilities.
 */
public class PlattScalingParameterFitter {

    private static final int DEFAULT_MAX_ITERATIONS = 100;
    private static final double DEFAULT_CONVERGENCE_TOLERANCE = 1e-7;
    private static final double MIN_SLOPE = 1e-4;

    private final boolean regularizeTargets;
    private final int maxIterations;
    private final double convergenceTolerance;

    /**
     * Constructs a fitter with default regularized targets and convergence settings.
     */
    public PlattScalingParameterFitter() {
        this(true, DEFAULT_MAX_ITERATIONS, DEFAULT_CONVERGENCE_TOLERANCE);
    }

    /**
     * Constructs a fitter with custom settings.
     *
     * @param regularizeTargets whether to use Platt's regularized targets
     * @param maxIterations maximum Newton iterations
     * @param convergenceTolerance convergence tolerance on parameter step size
     */
    public PlattScalingParameterFitter(boolean regularizeTargets, int maxIterations, double convergenceTolerance) {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be >= 1: " + maxIterations);
        }
        if (convergenceTolerance <= 0.0 || Double.isNaN(convergenceTolerance)) {
            throw new IllegalArgumentException("convergenceTolerance must be > 0: " + convergenceTolerance);
        }
        this.regularizeTargets = regularizeTargets;
        this.maxIterations = maxIterations;
        this.convergenceTolerance = convergenceTolerance;
    }

    /**
     * Fits Platt scaling parameters on a list of benchmark example results.
     *
     * @param results list of evaluated example results, must not be null
     * @param modelVersion version identifier for the fitted model
     * @param trainingSplit split name used for training
     * @return fitted PlattScalingModel
     */
    public PlattScalingModel fitFromResults(
            List<BenchmarkExampleResult> results,
            String modelVersion,
            String trainingSplit
    ) {
        Objects.requireNonNull(results, "results must not be null");
        List<CalibrationObservation> observations = results.stream()
                .filter(r -> r.predictedScore() != null)
                .map(CalibrationObservation::fromResult)
                .toList();
        return fit(observations, modelVersion, trainingSplit);
    }

    /**
     * Fits Platt scaling parameters on calibration observations.
     *
     * @param observations list of score-outcome observation pairs
     * @param modelVersion version identifier for the fitted model
     * @param trainingSplit split name used for training
     * @return fitted PlattScalingModel
     */
    public PlattScalingModel fit(
            List<CalibrationObservation> observations,
            String modelVersion,
            String trainingSplit
    ) {
        Objects.requireNonNull(observations, "observations must not be null");
        Objects.requireNonNull(modelVersion, "modelVersion must not be null");
        Objects.requireNonNull(trainingSplit, "trainingSplit must not be null");

        if (observations.size() < 4) {
            throw new IllegalArgumentException("At least 4 observations required for fitting: " + observations.size());
        }

        int positiveCount = 0;
        int negativeCount = 0;
        double minScore = Double.MAX_VALUE;
        double maxScore = -Double.MAX_VALUE;

        for (CalibrationObservation obs : observations) {
            if (obs.isCandidateCorrect()) {
                positiveCount++;
            } else {
                negativeCount++;
            }
            minScore = Math.min(minScore, obs.score());
            maxScore = Math.max(maxScore, obs.score());
        }

        if (positiveCount < 1) {
            throw new IllegalArgumentException("Fitting requires at least 1 positive observation");
        }
        if (negativeCount < 1) {
            throw new IllegalArgumentException("Fitting requires at least 1 negative observation");
        }
        if (maxScore - minScore < 1e-6) {
            throw new IllegalArgumentException("Scores have near-zero variance; cannot fit calibration model");
        }

        // Regularized targets (Platt 1999)
        double tPos = regularizeTargets ? ((double) (positiveCount + 1)) / (positiveCount + 2) : 1.0;
        double tNeg = regularizeTargets ? 1.0 / (negativeCount + 2) : 0.0;

        int n = observations.size();
        double[] scores = new double[n];
        double[] targets = new double[n];
        boolean[] actuals = new boolean[n];

        for (int i = 0; i < n; i++) {
            CalibrationObservation obs = observations.get(i);
            scores[i] = obs.score();
            actuals[i] = obs.isCandidateCorrect();
            targets[i] = obs.isCandidateCorrect() ? tPos : tNeg;
        }

        // Initial parameter estimates: A = 10.0, B = -10.0 * midpoint
        double avgScore = (minScore + maxScore) / 2.0;
        double a = 10.0;
        double b = -a * avgScore;

        double lambda = 1e-4;

        for (int iter = 0; iter < maxIterations; iter++) {
            double currentLoss = computeLoss(a, b, scores, targets);

            // Compute gradient and Hessian
            double gA = 0.0;
            double gB = 0.0;
            double hAA = 0.0;
            double hAB = 0.0;
            double hBB = 0.0;

            for (int i = 0; i < n; i++) {
                double p = computeSigmoid(a * scores[i] + b);
                double diff = p - targets[i];
                double w = Math.max(p * (1.0 - p), 1e-12);

                gA += diff * scores[i];
                gB += diff;
                hAA += w * scores[i] * scores[i];
                hAB += w * scores[i];
                hBB += w;
            }

            // Levenberg-Marquardt damping
            double hAAPrime = hAA + lambda;
            double hBBPrime = hBB + lambda;
            double det = (hAAPrime * hBBPrime) - (hAB * hAB);

            if (det <= 0.0 || Double.isNaN(det)) {
                lambda *= 10.0;
                continue;
            }

            // Newton direction: delta = - H^-1 * g
            double deltaA = -((hBBPrime * gA) - (hAB * gB)) / det;
            double deltaB = -((hAAPrime * gB) - (hAB * gA)) / det;

            // Backtracking line search ensuring A remains strictly positive and loss decreases
            double stepSize = 1.0;
            boolean stepAccepted = false;
            double bestA = a;
            double bestB = b;

            for (int lineStep = 0; lineStep < 10; lineStep++) {
                double candidateA = a + stepSize * deltaA;
                double candidateB = b + stepSize * deltaB;

                if (candidateA >= MIN_SLOPE) {
                    double candidateLoss = computeLoss(candidateA, candidateB, scores, targets);
                    if (candidateLoss < currentLoss) {
                        bestA = candidateA;
                        bestB = candidateB;
                        stepAccepted = true;
                        break;
                    }
                }
                stepSize *= 0.5;
            }

            if (stepAccepted) {
                double paramChange = Math.abs(bestA - a) + Math.abs(bestB - b);
                a = bestA;
                b = bestB;
                lambda = Math.max(lambda * 0.5, 1e-7);

                if (paramChange < convergenceTolerance) {
                    break;
                }
            } else {
                lambda *= 10.0;
                if (lambda > 1e6) {
                    // Reached damping limit, stop iteration
                    break;
                }
            }
        }

        // Enforce monotonicity invariant A > 0
        a = Math.max(a, MIN_SLOPE);

        // Compute final log-likelihood and Brier scores
        double logLikelihood = -computeLoss(a, b, scores, targets);

        double calibratedBrierSum = 0.0;
        double baselineBrierSum = 0.0;

        for (int i = 0; i < n; i++) {
            double p = computeSigmoid(a * scores[i] + b);
            double y = actuals[i] ? 1.0 : 0.0;
            calibratedBrierSum += (p - y) * (p - y);
            baselineBrierSum += (scores[i] - y) * (scores[i] - y);
        }

        double brierScore = calibratedBrierSum / n;
        double baselineBrierScore = baselineBrierSum / n;

        return new PlattScalingModel(
                a,
                b,
                modelVersion,
                trainingSplit,
                n,
                positiveCount,
                negativeCount,
                logLikelihood,
                brierScore,
                baselineBrierScore,
                false
        );
    }

    private double computeLoss(double a, double b, double[] scores, double[] targets) {
        double loss = 0.0;
        for (int i = 0; i < scores.length; i++) {
            double z = a * scores[i] + b;
            double t = targets[i];

            double absZ = Math.abs(z);
            double softplusNeg = Math.log1p(Math.exp(-absZ));

            double logP;
            double logOneMinusP;
            if (z >= 0.0) {
                logP = -softplusNeg;
                logOneMinusP = -z - softplusNeg;
            } else {
                logP = z - softplusNeg;
                logOneMinusP = -softplusNeg;
            }

            loss -= (t * logP + (1.0 - t) * logOneMinusP);
        }
        return loss;
    }

    private double computeSigmoid(double z) {
        if (z >= 40.0) {
            return 1.0;
        }
        if (z <= -40.0) {
            return 0.0;
        }
        return 1.0 / (1.0 + Math.exp(-z));
    }
}
