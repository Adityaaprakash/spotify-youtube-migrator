package com.spotifyyoutube.migrator.matching.benchmark.runner;

import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDataset;
import com.spotifyyoutube.migrator.matching.benchmark.pool.BenchmarkCandidatePoolProvider;
import com.spotifyyoutube.migrator.matching.benchmark.pool.ControlledDecoyCandidatePoolProvider;
import com.spotifyyoutube.migrator.matching.engine.DefaultTrackMatchingEngine;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchingEngine;
import com.spotifyyoutube.migrator.matching.normalization.AlbumNormalizer;
import com.spotifyyoutube.migrator.matching.normalization.ArtistNormalizer;
import com.spotifyyoutube.migrator.matching.normalization.DurationNormalizer;
import com.spotifyyoutube.migrator.matching.normalization.MetadataTokenizer;
import com.spotifyyoutube.migrator.matching.normalization.NormalizationOrchestrator;
import com.spotifyyoutube.migrator.matching.normalization.TitleNormalizer;

import java.util.Objects;

/**
 * Immutable configuration request for executing the benchmark runner.
 */
public record BenchmarkRunRequest(
        BenchmarkDataset dataset,
        TrackMatchingEngine matchingEngine,
        BenchmarkCandidatePoolProvider candidatePoolProvider,
        BenchmarkEligibilityPolicy eligibilityPolicy,
        NormalizationOrchestrator normalizationOrchestrator
) {

    public BenchmarkRunRequest {
        Objects.requireNonNull(dataset, "dataset must not be null");
        matchingEngine = matchingEngine != null ? matchingEngine : new DefaultTrackMatchingEngine();
        candidatePoolProvider = candidatePoolProvider != null
                ? candidatePoolProvider
                : new ControlledDecoyCandidatePoolProvider();
        eligibilityPolicy = eligibilityPolicy != null
                ? eligibilityPolicy
                : BenchmarkEligibilityPolicy.defaultPolicy();
        normalizationOrchestrator = normalizationOrchestrator != null
                ? normalizationOrchestrator
                : createDefaultNormalizationOrchestrator();
    }

    /**
     * Creates a run request using default engine, candidate pool provider, eligibility policy, and normalizer.
     *
     * @param dataset benchmark dataset
     * @return constructed BenchmarkRunRequest
     */
    public static BenchmarkRunRequest of(BenchmarkDataset dataset) {
        return new BenchmarkRunRequest(dataset, null, null, null, null);
    }

    /**
     * Creates a run request with custom candidate pool provider.
     */
    public static BenchmarkRunRequest of(
            BenchmarkDataset dataset,
            BenchmarkCandidatePoolProvider candidatePoolProvider
    ) {
        return new BenchmarkRunRequest(dataset, null, candidatePoolProvider, null, null);
    }

    /**
     * Creates a run request with custom matching engine and candidate pool provider.
     */
    public static BenchmarkRunRequest of(
            BenchmarkDataset dataset,
            TrackMatchingEngine matchingEngine,
            BenchmarkCandidatePoolProvider candidatePoolProvider
    ) {
        return new BenchmarkRunRequest(dataset, matchingEngine, candidatePoolProvider, null, null);
    }

    /**
     * Creates a run request targeting a specific dataset split.
     */
    public static BenchmarkRunRequest ofSplit(
            BenchmarkDataset dataset,
            com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDatasetSplit split
    ) {
        return new BenchmarkRunRequest(
                dataset,
                null,
                null,
                BenchmarkEligibilityPolicy.forSplit(split),
                null
        );
    }

    private static NormalizationOrchestrator createDefaultNormalizationOrchestrator() {
        TitleNormalizer titleNormalizer = new TitleNormalizer();
        ArtistNormalizer artistNormalizer = new ArtistNormalizer();
        MetadataTokenizer tokenizer = new MetadataTokenizer();
        return new NormalizationOrchestrator(
                titleNormalizer,
                artistNormalizer,
                new AlbumNormalizer(),
                new DurationNormalizer(),
                tokenizer
        );
    }
}
