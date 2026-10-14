package com.spotifyyoutube.migrator.matching.benchmark.calibration;

import com.spotifyyoutube.migrator.matching.engine.DefaultTrackMatchingEngine;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchingEngine;
import com.spotifyyoutube.migrator.matching.engine.calibration.DefaultMatchProbabilityCalibrator;
import com.spotifyyoutube.migrator.matching.engine.decision.DefaultMatchDecisionEngine;
import com.spotifyyoutube.migrator.matching.engine.feature.DefaultTrackFeatureExtractor;
import com.spotifyyoutube.migrator.matching.engine.ranking.DefaultCandidateRankingEngine;
import com.spotifyyoutube.migrator.matching.engine.scorer.DefaultCandidateSimilarityScorer;
import com.spotifyyoutube.migrator.matching.engine.scorer.DefaultCompositeScoreCalculator;

import java.util.Objects;

/**
 * Immutable representation of a fitted Platt scaling logistic calibration model:
 * $$P(\text{match} \mid s) = \frac{1}{1 + \exp(-(A \cdot s + B))}$$
 * where $s$ is the composite similarity score in {@code [0.0, 1.0]}.
 *
 * @param slopeA logistic slope coefficient ($A$), must be strictly positive ($A > 0$)
 * @param interceptB logistic intercept coefficient ($B$), must be finite
 * @param modelVersion model version identifier (e.g. "v1.0-platt-val"), must not be blank
 * @param trainingSplit split used for fitting (e.g. "VALIDATION"), must not be blank
 * @param sampleCount total observations used for fitting ($N \ge 4$)
 * @param positiveCount positive observations ($N_+ \ge 1$)
 * @param negativeCount negative observations ($N_- \ge 1$)
 * @param logLikelihood log-likelihood of fitted model on the training data
 * @param brierScore mean squared error of calibrated probabilities against binary ground truth
 * @param baselineBrierScore mean squared error of raw scores against binary ground truth
 * @param validated whether model has been verified on evaluation splits
 */
public record PlattScalingModel(
        double slopeA,
        double interceptB,
        String modelVersion,
        String trainingSplit,
        int sampleCount,
        int positiveCount,
        int negativeCount,
        double logLikelihood,
        double brierScore,
        double baselineBrierScore,
        boolean validated
) {

    public PlattScalingModel {
        if (Double.isNaN(slopeA) || Double.isInfinite(slopeA) || slopeA <= 0.0) {
            throw new IllegalArgumentException("slopeA must be finite and positive (> 0): " + slopeA);
        }
        if (Double.isNaN(interceptB) || Double.isInfinite(interceptB)) {
            throw new IllegalArgumentException("interceptB must be finite: " + interceptB);
        }
        Objects.requireNonNull(modelVersion, "modelVersion must not be null");
        if (modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion must not be blank");
        }
        modelVersion = modelVersion.trim();
        Objects.requireNonNull(trainingSplit, "trainingSplit must not be null");
        if (trainingSplit.isBlank()) {
            throw new IllegalArgumentException("trainingSplit must not be blank");
        }
        trainingSplit = trainingSplit.trim();
        if (sampleCount < 4) {
            throw new IllegalArgumentException("sampleCount must be >= 4: " + sampleCount);
        }
        if (positiveCount < 1) {
            throw new IllegalArgumentException("positiveCount must be >= 1: " + positiveCount);
        }
        if (negativeCount < 1) {
            throw new IllegalArgumentException("negativeCount must be >= 1: " + negativeCount);
        }
        if (sampleCount != positiveCount + negativeCount) {
            throw new IllegalArgumentException("sampleCount (" + sampleCount
                    + ") != positiveCount (" + positiveCount + ") + negativeCount (" + negativeCount + ")");
        }
        if (Double.isNaN(logLikelihood) || Double.isInfinite(logLikelihood)) {
            throw new IllegalArgumentException("logLikelihood must be finite: " + logLikelihood);
        }
        if (Double.isNaN(brierScore) || Double.isInfinite(brierScore) || brierScore < 0.0) {
            throw new IllegalArgumentException("brierScore must be finite and >= 0.0: " + brierScore);
        }
        if (Double.isNaN(baselineBrierScore) || Double.isInfinite(baselineBrierScore) || baselineBrierScore < 0.0) {
            throw new IllegalArgumentException("baselineBrierScore must be finite and >= 0.0: " + baselineBrierScore);
        }
    }

    /**
     * Computes the calibrated probability for a composite similarity score.
     *
     * @param score composite score in {@code [0.0, 1.0]}
     * @return calibrated probability in {@code [0.0, 1.0]}
     */
    public double predictProbability(double score) {
        if (Double.isNaN(score) || Double.isInfinite(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be finite and in [0.0, 1.0]: " + score);
        }
        double logit = (slopeA * score) + interceptB;
        if (logit >= 40.0) {
            return 1.0;
        }
        if (logit <= -40.0) {
            return 0.0;
        }
        double probability = 1.0 / (1.0 + Math.exp(-logit));
        return Math.max(0.0, Math.min(1.0, probability));
    }

    /**
     * Converts this fitted model into a {@link DefaultMatchProbabilityCalibrator}.
     *
     * @param markValidated whether to mark the calibrator as empirically validated
     * @return configured calibrator
     */
    public DefaultMatchProbabilityCalibrator toCalibrator(boolean markValidated) {
        return markValidated
                ? DefaultMatchProbabilityCalibrator.withValidatedPlattScaling(slopeA, interceptB, modelVersion)
                : DefaultMatchProbabilityCalibrator.withPlattScaling(slopeA, interceptB, modelVersion);
    }

    /**
     * Constructs a {@link TrackMatchingEngine} wired with this fitted calibration model.
     *
     * @param markValidated whether to mark the calibrator as empirically validated
     * @return configured matching engine
     */
    public TrackMatchingEngine createMatchingEngine(boolean markValidated) {
        DefaultMatchProbabilityCalibrator calibrator = toCalibrator(markValidated);
        return new DefaultTrackMatchingEngine(
                new DefaultTrackFeatureExtractor(),
                new DefaultCandidateSimilarityScorer(),
                new DefaultCompositeScoreCalculator(),
                new DefaultCandidateRankingEngine(),
                new DefaultMatchDecisionEngine(calibrator),
                calibrator
        );
    }

    /**
     * Computes Brier score reduction ($B_{\text{baseline}} - B_{\text{calibrated}}$).
     * Positive indicates calibration improved accuracy.
     *
     * @return Brier score improvement
     */
    public double brierImprovement() {
        return baselineBrierScore - brierScore;
    }

    /**
     * Computes Brier score reduction percentage.
     *
     * @return percentage reduction in Brier score
     */
    public double brierImprovementPercentage() {
        if (baselineBrierScore <= 0.0) {
            return 0.0;
        }
        return (brierImprovement() / baselineBrierScore) * 100.0;
    }
}
