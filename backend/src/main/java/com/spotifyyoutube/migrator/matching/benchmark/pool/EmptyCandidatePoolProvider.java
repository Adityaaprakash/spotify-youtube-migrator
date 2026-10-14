package com.spotifyyoutube.migrator.matching.benchmark.pool;

import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkExample;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;

import java.util.List;

/**
 * Candidate pool provider returning empty candidate collections for all examples.
 * <p>
 * Simulates total candidate discovery or retrieval failures to test the matching engine's
 * and benchmark runner's empty-pool handling and recall-failure reporting.
 */
public class EmptyCandidatePoolProvider implements BenchmarkCandidatePoolProvider {

    public static final String STRATEGY_NAME = "EMPTY_CANDIDATE_POOL";

    @Override
    public List<DestinationTrackCandidate> getCandidates(BenchmarkExample example) {
        return List.of();
    }

    @Override
    public boolean hasRealisticPool(BenchmarkExample example) {
        return false;
    }

    @Override
    public String strategyDescription() {
        return STRATEGY_NAME;
    }
}
