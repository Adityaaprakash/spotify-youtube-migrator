package com.spotifyyoutube.migrator.matching.benchmark;

/**
 * Deterministic dataset split assignment for evaluation and calibration.
 */
public enum BenchmarkDatasetSplit {

    /**
     * Development split: used for exploratory error analysis and heuristic formulation.
     */
    DEV,

    /**
     * Validation split: reserved for threshold tuning and empirical probability calibration (e.g., Platt scaling).
     */
    VALIDATION,

    /**
     * Held-out test split: strictly preserved for final, unbiased evaluation of matching accuracy.
     */
    TEST
}
