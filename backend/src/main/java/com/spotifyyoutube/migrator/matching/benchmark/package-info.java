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
 *   <li><b>Phase 8C (Benchmark Runner &amp; Candidate Pools):</b>
 *       {@link com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkRunner},
 *       {@link com.spotifyyoutube.migrator.matching.benchmark.runner.DefaultBenchmarkRunner},
 *       {@link com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkEligibilityPolicy},
 *       and {@link com.spotifyyoutube.migrator.matching.benchmark.pool.BenchmarkCandidatePoolProvider}.</li>
 *   <li><b>Phase 8D (Evaluation Metrics &amp; Error Analysis):</b>
 *       {@link com.spotifyyoutube.migrator.matching.benchmark.metric.RankingMetricsCalculator},
 *       {@link com.spotifyyoutube.migrator.matching.benchmark.metric.DecisionPolicyMetricsCalculator},
 *       {@link com.spotifyyoutube.migrator.matching.benchmark.metric.HighConfidenceMetricsCalculator},
 *       {@link com.spotifyyoutube.migrator.matching.benchmark.metric.CalibrationMetricsCalculator},
 *       {@link com.spotifyyoutube.migrator.matching.benchmark.metric.ErrorAnalysisCalculator},
 *       and {@link com.spotifyyoutube.migrator.matching.benchmark.report.BenchmarkReportGenerator}.</li>
 * </ul>
 */
package com.spotifyyoutube.migrator.matching.benchmark;
