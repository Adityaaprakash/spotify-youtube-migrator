package com.spotifyyoutube.migrator.matching.benchmark.pool;

import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkExample;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;

import java.util.List;

/**
 * Strategy interface for supplying candidate pools to the benchmark runner.
 * <p>
 * Decouples the benchmark execution from live network calls, ensuring deterministic,
 * offline evaluation while preserving candidate-pool integrity.
 */
public interface BenchmarkCandidatePoolProvider {

    /**
     * Obtains the destination candidate pool for a given benchmark example.
     *
     * @param example benchmark example under evaluation, must not be null
     * @return unmodifiable list of destination candidates (may be empty if no candidates available)
     */
    List<DestinationTrackCandidate> getCandidates(BenchmarkExample example);

    /**
     * Indicates whether this candidate pool represents a realistic, pre-recorded discovery pool
     * (as opposed to synthetic decoys or a single-candidate baseline).
     *
     * @param example benchmark example
     * @return true if realistic pre-recorded discovery candidates are available, false otherwise
     */
    default boolean hasRealisticPool(BenchmarkExample example) {
        return false;
    }

    /**
     * Identifies the sourcing strategy employed by this provider for transparency in reporting.
     *
     * @return strategy description identifier (e.g. "CONTROLLED_SYNTHETIC_DECOYS", "RECORDED_FIXTURE")
     */
    String strategyDescription();
}
