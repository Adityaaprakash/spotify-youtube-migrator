package com.spotifyyoutube.migrator.matching.engine;

import com.spotifyyoutube.migrator.matching.engine.calibration.CalibrationResult;
import com.spotifyyoutube.migrator.matching.engine.calibration.DefaultMatchProbabilityCalibrator;
import com.spotifyyoutube.migrator.matching.engine.calibration.MatchProbabilityCalibrator;
import com.spotifyyoutube.migrator.matching.engine.decision.DefaultMatchDecisionEngine;
import com.spotifyyoutube.migrator.matching.engine.decision.MatchDecisionEngine;
import com.spotifyyoutube.migrator.matching.engine.feature.CandidateTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.DefaultTrackFeatureExtractor;
import com.spotifyyoutube.migrator.matching.engine.feature.SourceTrackFeatures;
import com.spotifyyoutube.migrator.matching.engine.feature.TrackFeatureExtractor;
import com.spotifyyoutube.migrator.matching.engine.ranking.CandidateRankingEngine;
import com.spotifyyoutube.migrator.matching.engine.ranking.DefaultCandidateRankingEngine;
import com.spotifyyoutube.migrator.matching.engine.scorer.CandidateSimilarityScorer;
import com.spotifyyoutube.migrator.matching.engine.scorer.CompositeScoreCalculator;
import com.spotifyyoutube.migrator.matching.engine.scorer.DefaultCandidateSimilarityScorer;
import com.spotifyyoutube.migrator.matching.engine.scorer.DefaultCompositeScoreCalculator;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Default implementation of {@link TrackMatchingEngine}.
 * <p>
 * Orchestrates the full matching pipeline:
 * <ol>
 *   <li>Feature extraction for source track and destination candidates.</li>
 *   <li>Component similarity and version penalty scoring via {@link CandidateSimilarityScorer}.</li>
 *   <li>Composite numerical scoring via {@link CompositeScoreCalculator}.</li>
 *   <li>Match probability calibration via {@link MatchProbabilityCalibrator}.</li>
 *   <li>Deterministic candidate ranking via {@link CandidateRankingEngine}.</li>
 *   <li>Final matching decision and explanation generation via {@link MatchDecisionEngine}.</li>
 * </ol>
 */
@Component
public class DefaultTrackMatchingEngine implements TrackMatchingEngine {

    private final TrackFeatureExtractor featureExtractor;
    private final CandidateSimilarityScorer candidateSimilarityScorer;
    private final CompositeScoreCalculator compositeScoreCalculator;
    private final CandidateRankingEngine candidateRankingEngine;
    private final MatchDecisionEngine matchDecisionEngine;
    private final MatchProbabilityCalibrator probabilityCalibrator;

    /**
     * Constructs a fully parameterized track matching engine.
     *
     * @param featureExtractor feature extraction component
     * @param candidateSimilarityScorer component similarity and penalty scorer
     * @param compositeScoreCalculator composite score calculator
     * @param candidateRankingEngine candidate ranking engine
     * @param matchDecisionEngine match decision engine
     * @param probabilityCalibrator match probability calibrator
     */
    public DefaultTrackMatchingEngine(
            TrackFeatureExtractor featureExtractor,
            CandidateSimilarityScorer candidateSimilarityScorer,
            CompositeScoreCalculator compositeScoreCalculator,
            CandidateRankingEngine candidateRankingEngine,
            MatchDecisionEngine matchDecisionEngine,
            MatchProbabilityCalibrator probabilityCalibrator
    ) {
        this.featureExtractor = Objects.requireNonNull(featureExtractor, "TrackFeatureExtractor must not be null");
        this.candidateSimilarityScorer = Objects.requireNonNull(
                candidateSimilarityScorer, "CandidateSimilarityScorer must not be null");
        this.compositeScoreCalculator = Objects.requireNonNull(
                compositeScoreCalculator, "CompositeScoreCalculator must not be null");
        this.candidateRankingEngine = Objects.requireNonNull(
                candidateRankingEngine, "CandidateRankingEngine must not be null");
        this.matchDecisionEngine = Objects.requireNonNull(
                matchDecisionEngine, "MatchDecisionEngine must not be null");
        this.probabilityCalibrator = Objects.requireNonNull(
                probabilityCalibrator, "MatchProbabilityCalibrator must not be null");
    }

    /**
     * Constructs a track matching engine with default uncalibrated baseline calibrator.
     *
     * @param featureExtractor feature extraction component
     * @param candidateSimilarityScorer component similarity and penalty scorer
     * @param compositeScoreCalculator composite score calculator
     * @param candidateRankingEngine candidate ranking engine
     * @param matchDecisionEngine match decision engine
     */
    public DefaultTrackMatchingEngine(
            TrackFeatureExtractor featureExtractor,
            CandidateSimilarityScorer candidateSimilarityScorer,
            CompositeScoreCalculator compositeScoreCalculator,
            CandidateRankingEngine candidateRankingEngine,
            MatchDecisionEngine matchDecisionEngine
    ) {
        this(
                featureExtractor,
                candidateSimilarityScorer,
                compositeScoreCalculator,
                candidateRankingEngine,
                matchDecisionEngine,
                new DefaultMatchProbabilityCalibrator()
        );
    }

    /**
     * Constructs a track matching engine using default implementations of all components.
     */
    public DefaultTrackMatchingEngine() {
        this(
                new DefaultTrackFeatureExtractor(),
                new DefaultCandidateSimilarityScorer(),
                new DefaultCompositeScoreCalculator(),
                new DefaultCandidateRankingEngine(),
                new DefaultMatchDecisionEngine(),
                new DefaultMatchProbabilityCalibrator()
        );
    }

    @Override
    public TrackMatchResult matchTrack(TrackMatchRequest request) {
        Objects.requireNonNull(request, "TrackMatchRequest must not be null");

        List<DestinationTrackCandidate> candidates = request.candidates();
        if (candidates.isEmpty()) {
            return matchDecisionEngine.decide(request.sourceTrackId(), request.sourceTrack(), List.of());
        }

        SourceTrackFeatures sourceFeatures = featureExtractor.extractSourceFeatures(
                request.sourceTrackId(),
                request.sourceTrack()
        );

        List<CandidateMatchEvaluation> evaluations = new ArrayList<>(candidates.size());
        for (DestinationTrackCandidate candidate : candidates) {
            CandidateTrackFeatures candidateFeatures = featureExtractor.extractCandidateFeatures(
                    candidate,
                    request.sourceTrack()
            );

            MatchScoreBreakdown breakdown = candidateSimilarityScorer.scoreCandidate(
                    sourceFeatures,
                    candidateFeatures
            );
            Double compositeScore = compositeScoreCalculator.calculateCompositeScore(breakdown);

            CalibrationResult calibrationResult = probabilityCalibrator.calibrate(compositeScore, breakdown);
            Double calibratedProbability = calibrationResult.calibratedProbability().orElse(null);

            MatchConfidence confidence = determineConfidence(compositeScore, calibratedProbability);
            List<String> candidateReasons = buildCandidateReasons(breakdown, compositeScore, calibrationResult);

            CandidateMatchEvaluation evaluation = CandidateMatchEvaluation.of(
                    candidate,
                    compositeScore,
                    calibratedProbability,
                    confidence,
                    breakdown,
                    candidateReasons,
                    candidateFeatures
            );
            evaluations.add(evaluation);
        }

        List<CandidateMatchEvaluation> rankedEvaluations = candidateRankingEngine.rankCandidates(evaluations);

        return matchDecisionEngine.decide(
                request.sourceTrackId(),
                request.sourceTrack(),
                rankedEvaluations
        );
    }

    private MatchConfidence determineConfidence(Double score, Double calibratedProbability) {
        if (score == null) {
            return MatchConfidence.POOR;
        }
        if (calibratedProbability != null) {
            if (score >= DefaultMatchDecisionEngine.DEFAULT_EXCELLENT_THRESHOLD && calibratedProbability >= 0.85) {
                return MatchConfidence.EXCELLENT;
            }
            if (score >= DefaultMatchDecisionEngine.DEFAULT_MATCH_THRESHOLD && calibratedProbability >= 0.70) {
                return MatchConfidence.GOOD;
            }
            return MatchConfidence.POOR;
        }
        if (score >= DefaultMatchDecisionEngine.DEFAULT_EXCELLENT_THRESHOLD) {
            return MatchConfidence.EXCELLENT;
        }
        if (score >= DefaultMatchDecisionEngine.DEFAULT_MATCH_THRESHOLD) {
            return MatchConfidence.GOOD;
        }
        return MatchConfidence.POOR;
    }

    private List<String> buildCandidateReasons(
            MatchScoreBreakdown breakdown,
            Double compositeScore,
            CalibrationResult calibrationResult
    ) {
        List<String> reasons = new ArrayList<>();
        if (breakdown.titleScore() != null) {
            if (breakdown.titleScore() >= 0.95) {
                reasons.add("Exact or near-exact title match");
            } else if (breakdown.titleScore() < 0.60) {
                reasons.add("Low title similarity");
            }
        }
        if (breakdown.artistScore() != null) {
            if (breakdown.artistScore() >= 0.95) {
                reasons.add("Exact or near-exact artist match");
            } else if (breakdown.artistScore() < 0.60) {
                reasons.add("Low artist similarity");
            }
        }
        if (breakdown.durationScore() != null && breakdown.durationScore() >= 0.90) {
            reasons.add("Strong playback duration alignment");
        }
        if (breakdown.albumScore() != null && breakdown.albumScore() >= 0.85) {
            reasons.add("Strong album name correspondence");
        }
        if (breakdown.officialSignalScore() != null && breakdown.officialSignalScore() > 0.0) {
            reasons.add("Official provenance signal verified");
        }
        if (breakdown.versionPenalty() != null && breakdown.versionPenalty() > 0.0) {
            reasons.add("Recording version discrepancy penalty: " + breakdown.versionPenalty());
        }
        if (calibrationResult != null && calibrationResult.isAvailable()) {
            if (calibrationResult.isValidated()) {
                calibrationResult.calibratedProbability().ifPresent(prob ->
                        reasons.add(String.format(
                                java.util.Locale.ROOT,
                                "Calibrated match probability: %.4f (%s)",
                                prob,
                                calibrationResult.calibrationMethod()
                        ))
                );
            } else {
                calibrationResult.configuredProbability().ifPresent(prob ->
                        reasons.add(String.format(
                                java.util.Locale.ROOT,
                                "Unvalidated probability transformation: %.4f (%s, unvalidated)",
                                prob,
                                calibrationResult.calibrationMethod()
                        ))
                );
            }
        }
        return reasons;
    }
}
