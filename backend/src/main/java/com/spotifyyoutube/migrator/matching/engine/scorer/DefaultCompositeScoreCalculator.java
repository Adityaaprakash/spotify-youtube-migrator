package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.MatchScoreBreakdown;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Default implementation of {@link CompositeScoreCalculator}.
 * <p>
 * Evaluates component evidence using a normalized linear combination with dynamic weight re-normalization:
 * <ul>
 *   <li>Title Similarity: 40% (0.40)</li>
 *   <li>Artist Similarity: 30% (0.30)</li>
 *   <li>Duration Alignment: 10% (0.10)</li>
 *   <li>Album Alignment: 10% (0.10)</li>
 *   <li>Official Provenance: 10% (0.10)</li>
 * </ul>
 * <b>Honest Missing-Data Semantics:</b> When secondary dimensions (such as album or duration) are
 * {@code null}, weights are dynamically re-normalized over the active dimensions. Missing evidence
 * is never coerced to zero or assumed to be an exact match.
 * <p>
 * <b>Version Penalty Application:</b> The aggregate {@code versionPenalty} from Phase 7F is deducted
 * from the weighted base score once, and the resulting composite score is clamped strictly to {@code [0.0, 1.0]}.
 */
@Component
public class DefaultCompositeScoreCalculator implements CompositeScoreCalculator {

    public static final double DEFAULT_TITLE_WEIGHT = 0.40;
    public static final double DEFAULT_ARTIST_WEIGHT = 0.30;
    public static final double DEFAULT_DURATION_WEIGHT = 0.10;
    public static final double DEFAULT_ALBUM_WEIGHT = 0.10;
    public static final double DEFAULT_OFFICIAL_WEIGHT = 0.10;

    private final double titleWeight;
    private final double artistWeight;
    private final double durationWeight;
    private final double albumWeight;
    private final double officialWeight;

    public DefaultCompositeScoreCalculator() {
        this(DEFAULT_TITLE_WEIGHT, DEFAULT_ARTIST_WEIGHT, DEFAULT_DURATION_WEIGHT, DEFAULT_ALBUM_WEIGHT, DEFAULT_OFFICIAL_WEIGHT);
    }

    public DefaultCompositeScoreCalculator(
            double titleWeight,
            double artistWeight,
            double durationWeight,
            double albumWeight,
            double officialWeight
    ) {
        validateWeight("titleWeight", titleWeight);
        validateWeight("artistWeight", artistWeight);
        validateWeight("durationWeight", durationWeight);
        validateWeight("albumWeight", albumWeight);
        validateWeight("officialWeight", officialWeight);

        this.titleWeight = titleWeight;
        this.artistWeight = artistWeight;
        this.durationWeight = durationWeight;
        this.albumWeight = albumWeight;
        this.officialWeight = officialWeight;
    }

    @Override
    public Double calculateBaseScore(MatchScoreBreakdown breakdown) {
        Objects.requireNonNull(breakdown, "MatchScoreBreakdown must not be null");

        Double titleScore = breakdown.titleScore();
        Double artistScore = breakdown.artistScore();
        Double durationScore = breakdown.durationScore();
        Double albumScore = breakdown.albumScore();
        Double officialScore = breakdown.officialSignalScore();

        validateScore("titleScore", titleScore);
        validateScore("artistScore", artistScore);
        validateScore("durationScore", durationScore);
        validateScore("albumScore", albumScore);
        validateScore("officialSignalScore", officialScore);

        double activeWeightSum = 0.0;
        double weightedScoreSum = 0.0;

        if (titleScore != null) {
            activeWeightSum += titleWeight;
            weightedScoreSum += titleWeight * titleScore;
        }
        if (artistScore != null) {
            activeWeightSum += artistWeight;
            weightedScoreSum += artistWeight * artistScore;
        }
        if (durationScore != null) {
            activeWeightSum += durationWeight;
            weightedScoreSum += durationWeight * durationScore;
        }
        if (albumScore != null) {
            activeWeightSum += albumWeight;
            weightedScoreSum += albumWeight * albumScore;
        }
        if (officialScore != null) {
            activeWeightSum += officialWeight;
            weightedScoreSum += officialWeight * officialScore;
        }

        if (activeWeightSum <= 0.0) {
            return 0.0;
        }

        return roundToFourDecimals(weightedScoreSum / activeWeightSum);
    }

    @Override
    public Double calculateCompositeScore(MatchScoreBreakdown breakdown) {
        Objects.requireNonNull(breakdown, "MatchScoreBreakdown must not be null");

        Double versionPenalty = breakdown.versionPenalty();
        validatePenalty("versionPenalty", versionPenalty);

        double baseScore = calculateBaseScore(breakdown);

        // Apply version penalty deduction once
        double penalty = versionPenalty != null ? versionPenalty : 0.0;
        double finalScore = Math.max(0.0, Math.min(1.0, baseScore - penalty));

        return roundToFourDecimals(finalScore);
    }

    private void validateScore(String name, Double score) {
        if (score == null) {
            return;
        }
        if (score.isNaN() || score.isInfinite()) {
            throw new IllegalArgumentException(name + " must be a finite number: " + score);
        }
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0.0 and 1.0: " + score);
        }
    }

    private void validatePenalty(String name, Double penalty) {
        if (penalty == null) {
            return;
        }
        if (penalty.isNaN() || penalty.isInfinite()) {
            throw new IllegalArgumentException(name + " must be a finite number: " + penalty);
        }
        if (penalty < 0.0 || penalty > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0.0 and 1.0: " + penalty);
        }
    }

    private void validateWeight(String name, double weight) {
        if (Double.isNaN(weight) || Double.isInfinite(weight) || weight < 0.0) {
            throw new IllegalArgumentException(name + " must be a non-negative finite number: " + weight);
        }
    }

    private double roundToFourDecimals(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
