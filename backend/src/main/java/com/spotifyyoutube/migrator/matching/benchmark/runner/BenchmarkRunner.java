package com.spotifyyoutube.migrator.matching.benchmark.runner;

/**
 * Top-level contract for the benchmark runner.
 * <p>
 * Evaluates the track matching engine across a ground-truth dataset and returns
 * structured, immutable evaluation results ready for metric calculation.
 */
public interface BenchmarkRunner {

    /**
     * Executes the benchmark evaluation run with the given configuration request.
     *
     * @param request configuration request containing dataset, matching engine, and pool provider
     * @return structured, immutable run result
     */
    BenchmarkRunResult run(BenchmarkRunRequest request);
}
