package com.spotifyyoutube.migrator.matching.engine.decision;

import com.spotifyyoutube.migrator.matching.engine.CandidateMatchEvaluation;
import com.spotifyyoutube.migrator.matching.engine.MatchConfidence;
import com.spotifyyoutube.migrator.matching.engine.MatchDecision;
import com.spotifyyoutube.migrator.matching.engine.MatchScoreBreakdown;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchResult;
import com.spotifyyoutube.migrator.matching.engine.scorer.CompositeScoreCalculator;
import com.spotifyyoutube.migrator.matching.engine.scorer.DefaultCompositeScoreCalculator;
import com.spotifyyoutube.migrator.matching.normalization.NormalizedTrackMetadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Default implementation of {@link MatchDecisionEngine}.
 * <p>
 * Applies transparent, threshold-based matching rules to ranked candidates:
 * <ul>
 *   <li><b>Empty Candidates:</b> Resolves to {@link MatchDecision#NO_MATCH}.</li>
 *   <li><b>Low Score (&lt; 0.50):</b> Resolves to {@link MatchDecision#NO_MATCH} (below minimum viable threshold).</li>
 *   <li><b>Borderline Score (0.50 to &lt; 0.75):</b> Resolves to {@link MatchDecision#REVIEW_REQUIRED}.</li>
 *   <li><b>Version Penalty Mismatch (&gt; 0.10):</b> Resolves to {@link MatchDecision#REVIEW_REQUIRED} despite high score.</li>
 *   <li><b>Ambiguity Margin (&lt; 0.05 between top two):</b> Resolves to {@link MatchDecision#REVIEW_REQUIRED}.</li>
 *   <li><b>Unambiguous Top Candidate (&ge; 0.75):</b> Resolves to {@link MatchDecision#MATCH}
 *       with {@link MatchConfidence#EXCELLENT} (&ge; 0.90) or {@link MatchConfidence#GOOD} (0.75 to &lt; 0.90).</li>
 * </ul>
 */
@Component
public class DefaultMatchDecisionEngine implements MatchDecisionEngine {

    public static final double DEFAULT_MATCH_THRESHOLD = 0.75;
    public static final double DEFAULT_MINIMUM_VIABLE_THRESHOLD = 0.50;
    public static final double DEFAULT_AMBIGUITY_MARGIN = 0.05;
    public static final double DEFAULT_MAX_PENALTY_FOR_MATCH = 0.10;
    public static final double DEFAULT_EXCELLENT_THRESHOLD = 0.90;

    private final double matchThreshold;
    private final double minimumViableThreshold;
    private final double ambiguityMargin;
    private final double maxPenaltyForMatch;
    private final double excellentThreshold;
    private final CompositeScoreCalculator compositeScoreCalculator;

    public DefaultMatchDecisionEngine() {
        this(
                DEFAULT_MATCH_THRESHOLD,
                DEFAULT_MINIMUM_VIABLE_THRESHOLD,
                DEFAULT_AMBIGUITY_MARGIN,
                DEFAULT_MAX_PENALTY_FOR_MATCH,
                DEFAULT_EXCELLENT_THRESHOLD,
                new DefaultCompositeScoreCalculator()
        );
    }

    public DefaultMatchDecisionEngine(
            double matchThreshold,
            double minimumViableThreshold,
            double ambiguityMargin,
            double maxPenaltyForMatch,
            double excellentThreshold
    ) {
        this(
                matchThreshold,
                minimumViableThreshold,
                ambiguityMargin,
                maxPenaltyForMatch,
                excellentThreshold,
                new DefaultCompositeScoreCalculator()
        );
    }

    public DefaultMatchDecisionEngine(
            double matchThreshold,
            double minimumViableThreshold,
            double ambiguityMargin,
            double maxPenaltyForMatch,
            double excellentThreshold,
            CompositeScoreCalculator compositeScoreCalculator
    ) {
        this.matchThreshold = matchThreshold;
        this.minimumViableThreshold = minimumViableThreshold;
        this.ambiguityMargin = ambiguityMargin;
        this.maxPenaltyForMatch = maxPenaltyForMatch;
        this.excellentThreshold = excellentThreshold;
        this.compositeScoreCalculator = compositeScoreCalculator != null
                ? compositeScoreCalculator
                : new DefaultCompositeScoreCalculator();
    }

    @Override
    public TrackMatchResult decide(
            String sourceTrackId,
            NormalizedTrackMetadata sourceTrack,
            List<CandidateMatchEvaluation> rankedEvaluations
    ) {
        Objects.requireNonNull(sourceTrack, "NormalizedTrackMetadata must not be null");
        Objects.requireNonNull(rankedEvaluations, "Ranked evaluations list must not be null");

        // 1. Case: No candidates available
        if (rankedEvaluations.isEmpty()) {
            return TrackMatchResult.noMatch(sourceTrackId, sourceTrack, List.of("No destination candidates discovered"));
        }

        CandidateMatchEvaluation top = rankedEvaluations.get(0);
        double topScore = top.overallScore() != null ? top.overallScore() : 0.0;
        MatchScoreBreakdown topBreakdown = top.scoreBreakdown();
        double topPenalty = topBreakdown != null && topBreakdown.versionPenalty() != null
                ? topBreakdown.versionPenalty()
                : 0.0;

        double baseScore = calculateBaseScore(top, topScore, topPenalty);

        List<String> reasons = new ArrayList<>();

        // 2. Case: Version penalty discrepancy on viable candidate
        if (topPenalty > maxPenaltyForMatch && baseScore >= minimumViableThreshold) {
            reasons.add("Top candidate '" + top.candidateTitle()
                    + "' exhibits recording version discrepancy penalty (" + format(topPenalty) + ")");
            reasons.add("Version variance requires manual user review");

            return new TrackMatchResult(
                    sourceTrackId,
                    sourceTrack,
                    MatchDecision.REVIEW_REQUIRED,
                    top.candidate(),
                    topScore,
                    null,
                    MatchConfidence.POOR,
                    topBreakdown,
                    rankedEvaluations,
                    reasons
            );
        }

        // 3. Case: Below minimum viable threshold
        if (topScore < minimumViableThreshold) {
            reasons.add("Top candidate '" + top.candidateTitle() + "' score (" + format(topScore)
                    + ") is below minimum viable threshold (" + format(minimumViableThreshold) + ")");
            reasons.add("No candidate satisfied minimum similarity requirements");

            return new TrackMatchResult(
                    sourceTrackId,
                    sourceTrack,
                    MatchDecision.NO_MATCH,
                    null,
                    topScore,
                    null,
                    MatchConfidence.POOR,
                    topBreakdown,
                    rankedEvaluations,
                    reasons
            );
        }

        // 4. Case: Below match threshold (borderline)
        if (topScore < matchThreshold) {
            reasons.add("Top candidate '" + top.candidateTitle() + "' score (" + format(topScore)
                    + ") is below automated match threshold (" + format(matchThreshold) + ")");
            reasons.add("Borderline similarity requires manual user review");

            return new TrackMatchResult(
                    sourceTrackId,
                    sourceTrack,
                    MatchDecision.REVIEW_REQUIRED,
                    top.candidate(),
                    topScore,
                    null,
                    MatchConfidence.POOR,
                    topBreakdown,
                    rankedEvaluations,
                    reasons
            );
        }

        // 5. Case: Close competing runner-up within ambiguity margin
        if (rankedEvaluations.size() >= 2) {
            CandidateMatchEvaluation runnerUp = rankedEvaluations.get(1);
            double runnerUpScore = runnerUp.overallScore() != null ? runnerUp.overallScore() : 0.0;
            double margin = topScore - runnerUpScore;

            if (margin < ambiguityMargin) {
                reasons.add("Ambiguous match: leading candidate '" + top.candidateTitle() + "' (" + format(topScore)
                        + ") and runner-up '" + runnerUp.candidateTitle() + "' (" + format(runnerUpScore)
                        + ") are within ambiguity margin (" + format(ambiguityMargin) + ")");
                reasons.add("Close candidate competition requires manual user review");

                return new TrackMatchResult(
                        sourceTrackId,
                        sourceTrack,
                        MatchDecision.REVIEW_REQUIRED,
                        top.candidate(),
                        topScore,
                        null,
                        MatchConfidence.GOOD,
                        topBreakdown,
                        rankedEvaluations,
                        reasons
                );
            }
        }

        // 6. Case: Unambiguous match
        MatchConfidence confidence = topScore >= excellentThreshold
                ? MatchConfidence.EXCELLENT
                : MatchConfidence.GOOD;

        reasons.add("High-confidence match: '" + top.candidateTitle()
                + "' meets all automated migration criteria (score: " + format(topScore) + ", confidence: " + confidence + ")");
        reasons.add("No significant version penalties or competing runner-up candidates");

        return new TrackMatchResult(
                sourceTrackId,
                sourceTrack,
                MatchDecision.MATCH,
                top.candidate(),
                topScore,
                null,
                confidence,
                topBreakdown,
                rankedEvaluations,
                reasons
        );
    }

    private double calculateBaseScore(CandidateMatchEvaluation top, double topScore, double topPenalty) {
        if (top != null && top.scoreBreakdown() != null) {
            MatchScoreBreakdown b = top.scoreBreakdown();
            if (b.hasTitleScore() || b.hasArtistScore() || b.hasDurationScore()
                    || b.hasAlbumScore() || b.hasOfficialSignalScore()) {
                return compositeScoreCalculator.calculateBaseScore(b);
            }
        }
        return topScore + topPenalty;
    }

    private String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }
}
