package com.spotifyyoutube.migrator.matching.benchmark.pool;

import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkExample;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Candidate pool provider backed by pre-recorded, version-controlled candidate fixtures.
 * <p>
 * Allows supplying realistic YouTube candidate pools mapped by benchmark example ID.
 * If an example has no recorded fixture, returns an empty candidate list or falls back
 * to a secondary delegate if configured.
 */
public class FixtureCandidatePoolProvider implements BenchmarkCandidatePoolProvider {

    public static final String STRATEGY_NAME = "RECORDED_FIXTURE";

    private final Map<String, List<DestinationTrackCandidate>> fixtureMap;
    private final BenchmarkCandidatePoolProvider fallbackProvider;

    public FixtureCandidatePoolProvider(Map<String, List<DestinationTrackCandidate>> fixtureMap) {
        this(fixtureMap, null);
    }

    public FixtureCandidatePoolProvider(
            Map<String, List<DestinationTrackCandidate>> fixtureMap,
            BenchmarkCandidatePoolProvider fallbackProvider
    ) {
        this.fixtureMap = fixtureMap != null ? new HashMap<>(fixtureMap) : Map.of();
        this.fallbackProvider = fallbackProvider;
    }

    @Override
    public List<DestinationTrackCandidate> getCandidates(BenchmarkExample example) {
        Objects.requireNonNull(example, "example must not be null");
        List<DestinationTrackCandidate> pool = fixtureMap.get(example.id());
        if (pool != null) {
            return Collections.unmodifiableList(pool);
        }
        if (fallbackProvider != null) {
            return fallbackProvider.getCandidates(example);
        }
        return List.of();
    }

    @Override
    public boolean hasRealisticPool(BenchmarkExample example) {
        if (example != null && fixtureMap.containsKey(example.id())) {
            return true;
        }
        return fallbackProvider != null && fallbackProvider.hasRealisticPool(example);
    }

    @Override
    public String strategyDescription() {
        return STRATEGY_NAME;
    }

    public int getFixtureCount() {
        return fixtureMap.size();
    }
}
