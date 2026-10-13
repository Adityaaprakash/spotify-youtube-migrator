/**
 * Phase 8: Matching Evaluation and Benchmark Subsystem.
 * <p>
 * Provides offline, typed benchmark dataset models, schema validation, and evaluation contracts
 * for measuring the quality of the frozen Phase 7 Matching Engine against verifiable ground truth:
 * <ul>
 *   <li><b>Phase 8A (Benchmark Architecture &amp; Contracts):</b>
 *       {@link com.spotifyyoutube.migrator.matching.benchmark.BenchmarkExample},
 *       {@link com.spotifyyoutube.migrator.matching.benchmark.BenchmarkSourceTrack},
 *       {@link com.spotifyyoutube.migrator.matching.benchmark.BenchmarkTargetCandidate},
 *       {@link com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDataset},
 *       {@link com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDatasetValidator},
 *       and {@link com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDatasetLoader}.</li>
 *   <li><b>Phase 8B (Ground-Truth Benchmark Dataset):</b> Version-controlled, representative
 *       ground-truth dataset with positive matches and legitimate non-matches across diverse
 *       track categories (DEV, VALIDATION, and TEST splits).</li>
 *   <li><b>Phase 8C (Future Benchmark Runner Contract):</b> Decoupled integration contract
 *       where a benchmark runner evaluates matching accuracy (Top-1, Top-3, Precision, Recall,
 *       Brier score, calibration curve) against fixed ground truth.</li>
 * </ul>
 */
package com.spotifyyoutube.migrator.matching.benchmark;
