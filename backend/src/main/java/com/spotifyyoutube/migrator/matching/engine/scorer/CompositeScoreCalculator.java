package com.spotifyyoutube.migrator.matching.engine.scorer;

import com.spotifyyoutube.migrator.matching.engine.MatchScoreBreakdown;

/**
 * Strategy interface for calculating the overall composite similarity score from
 * a populated {@link MatchScoreBreakdown}.
 * <p>
 * Bounded strictly in {@code [0.0, 1.0]}.
 */
public interface CompositeScoreCalculator {

    /**
     * Calculates the composite similarity score for the given score breakdown.
     *
     * @param breakdown score breakdown containing component similarity scores and version penalties, must not be null
     * @return bounded composite similarity score in {@code [0.0, 1.0]}
     * @throws NullPointerException if breakdown is null
     * @throws IllegalArgumentException if any numeric score in breakdown is NaN or Infinite
     */
    Double calculateCompositeScore(MatchScoreBreakdown breakdown);

    /**
     * Calculates the re-normalized weighted base similarity score before any
     * version penalty deduction is applied.
     *
     * @param breakdown score breakdown containing component similarity scores, must not be null
     * @return re-normalized base similarity score in {@code [0.0, 1.0]}
     * @throws NullPointerException if breakdown is null
     * @throws IllegalArgumentException if any numeric score in breakdown is NaN or Infinite
     */
    Double calculateBaseScore(MatchScoreBreakdown breakdown);
}
