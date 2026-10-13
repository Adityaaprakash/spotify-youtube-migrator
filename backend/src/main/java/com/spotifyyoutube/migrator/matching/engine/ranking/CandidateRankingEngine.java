package com.spotifyyoutube.migrator.matching.engine.ranking;

import com.spotifyyoutube.migrator.matching.engine.CandidateMatchEvaluation;

import java.util.List;

/**
 * Strategy interface for ranking evaluated destination candidates.
 * Produces an ordered, unmodifiable list sorted in descending match priority.
 */
public interface CandidateRankingEngine {

    /**
     * Ranks evaluated candidates in descending order of match quality.
     * Uses deterministic tie-breaking rules and returns an unmodifiable list.
     *
     * @param evaluations list of evaluated candidates, must not be null
     * @return unmodifiable list of evaluated candidates in ranked order
     * @throws NullPointerException if evaluations is null
     */
    List<CandidateMatchEvaluation> rankCandidates(List<CandidateMatchEvaluation> evaluations);
}
