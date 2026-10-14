package com.spotifyyoutube.migrator.matching.benchmark.calibration;

import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDataset;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDatasetLoader;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDatasetSplit;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkExample;
import com.spotifyyoutube.migrator.matching.benchmark.metric.BenchmarkMetrics;
import com.spotifyyoutube.migrator.matching.benchmark.metric.BenchmarkMetricsCalculator;
import com.spotifyyoutube.migrator.matching.benchmark.pool.BenchmarkCandidatePoolProvider;
import com.spotifyyoutube.migrator.matching.benchmark.pool.ControlledDecoyCandidatePoolProvider;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkExampleResult;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkRunRequest;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkRunResult;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkRunner;
import com.spotifyyoutube.migrator.matching.benchmark.runner.DefaultBenchmarkRunner;
import com.spotifyyoutube.migrator.matching.engine.TrackMatchingEngine;

import java.util.List;
import java.util.Objects;

/**
 * End-to-end execution pipeline for Phase 8E empirical probability calibration and threshold optimization.
 * <p>
 * Enforces scientific rigor and benchmark data hygiene:
 * <ol>
 *   <li>Evaluates the baseline matching engine on the {@code VALIDATION} split.</li>
 *   <li>Extracts score-outcome observations and fits Platt scaling parameters $(A, B)$ using
 *       {@link PlattScalingParameterFitter}.</li>
 *   <li>Sweeps decision policy thresholds on {@code VALIDATION} observations to locate optimal
 *       operating points subject to target precision constraints via {@link DecisionThresholdOptimizer}.</li>
 *   <li>Constructs a validated {@link TrackMatchingEngine} wired with the empirical calibration model.</li>
 *   <li>Executes the calibrated engine against the held-out {@code TEST} split to evaluate
 *       out-of-sample generalization (Brier score, ECE, reliability diagram, and safety audit).</li>
 *   <li>Packages the findings into an immutable {@link BenchmarkCalibrationReport}.</li>
 * </ol>
 */
public class BenchmarkCalibrationPipeline {

    public static final double DEFAULT_TARGET_PRECISION = 0.95;
    public static final String DEFAULT_MODEL_VERSION = "v1.0-platt-val";

    private final BenchmarkRunner benchmarkRunner;
    private final BenchmarkCandidatePoolProvider defaultCandidatePoolProvider;
    private final PlattScalingParameterFitter parameterFitter;
    private final DecisionThresholdOptimizer thresholdOptimizer;
    private final BenchmarkMetricsCalculator metricsCalculator;

    public BenchmarkCalibrationPipeline() {
        this(
                new DefaultBenchmarkRunner(),
                new ControlledDecoyCandidatePoolProvider(),
                new PlattScalingParameterFitter(),
                new DecisionThresholdOptimizer(),
                new BenchmarkMetricsCalculator()
        );
    }

    public BenchmarkCalibrationPipeline(
            BenchmarkRunner benchmarkRunner,
            BenchmarkCandidatePoolProvider defaultCandidatePoolProvider,
            PlattScalingParameterFitter parameterFitter,
            DecisionThresholdOptimizer thresholdOptimizer,
            BenchmarkMetricsCalculator metricsCalculator
    ) {
        this.benchmarkRunner = Objects.requireNonNull(benchmarkRunner, "benchmarkRunner must not be null");
        this.defaultCandidatePoolProvider = Objects.requireNonNull(
                defaultCandidatePoolProvider, "defaultCandidatePoolProvider must not be null");
        this.parameterFitter = Objects.requireNonNull(parameterFitter, "parameterFitter must not be null");
        this.thresholdOptimizer = Objects.requireNonNull(thresholdOptimizer, "thresholdOptimizer must not be null");
        this.metricsCalculator = Objects.requireNonNull(metricsCalculator, "metricsCalculator must not be null");
    }

    /**
     * Executes the calibration pipeline on the default ground-truth dataset with default settings.
     *
     * @return generated BenchmarkCalibrationReport
     */
    public BenchmarkCalibrationReport runDefault() {
        BenchmarkDataset dataset = new BenchmarkDatasetLoader().loadDefault();
        return runCalibration(dataset, defaultCandidatePoolProvider, DEFAULT_TARGET_PRECISION);
    }

    /**
     * Executes the calibration pipeline on the given dataset with default pool provider and target precision.
     *
     * @param dataset ground-truth dataset containing VALIDATION and TEST splits
     * @return generated BenchmarkCalibrationReport
     */
    public BenchmarkCalibrationReport runCalibration(BenchmarkDataset dataset) {
        return runCalibration(dataset, defaultCandidatePoolProvider, DEFAULT_TARGET_PRECISION);
    }

    /**
     * Executes the calibration pipeline on the given dataset with custom target precision.
     *
     * @param dataset ground-truth dataset containing VALIDATION and TEST splits
     * @param targetPrecision desired precision threshold constraint (e.g. 0.95 or 0.98)
     * @return generated BenchmarkCalibrationReport
     */
    public BenchmarkCalibrationReport runCalibration(BenchmarkDataset dataset, double targetPrecision) {
        return runCalibration(dataset, defaultCandidatePoolProvider, targetPrecision);
    }

    /**
     * Executes the calibration pipeline with full customization of candidate pool provider and precision.
     *
     * @param dataset ground-truth dataset containing VALIDATION and TEST splits
     * @param candidatePoolProvider provider for evaluation candidate pools
     * @param targetPrecision desired precision threshold constraint
     * @return generated BenchmarkCalibrationReport
     */
    public BenchmarkCalibrationReport runCalibration(
            BenchmarkDataset dataset,
            BenchmarkCandidatePoolProvider candidatePoolProvider,
            double targetPrecision
    ) {
        Objects.requireNonNull(dataset, "dataset must not be null");
        Objects.requireNonNull(candidatePoolProvider, "candidatePoolProvider must not be null");
        if (Double.isNaN(targetPrecision) || targetPrecision <= 0.0 || targetPrecision > 1.0) {
            throw new IllegalArgumentException("targetPrecision must be in (0.0, 1.0]: " + targetPrecision);
        }

        long startTime = System.currentTimeMillis();

        // 1. Split Isolation: VALIDATION and TEST subsets
        List<BenchmarkExample> valExamples = dataset.getExamplesBySplit(BenchmarkDatasetSplit.VALIDATION);
        if (valExamples.isEmpty()) {
            throw new IllegalStateException("Benchmark dataset contains no examples in VALIDATION split");
        }
        BenchmarkDataset valDataset = new BenchmarkDataset(
                dataset.version(),
                dataset.description() + " [VALIDATION]",
                dataset.createdAt(),
                valExamples
        );

        List<BenchmarkExample> testExamples = dataset.getExamplesBySplit(BenchmarkDatasetSplit.TEST);
        if (testExamples.isEmpty()) {
            throw new IllegalStateException("Benchmark dataset contains no examples in TEST split");
        }
        BenchmarkDataset testDataset = new BenchmarkDataset(
                dataset.version(),
                dataset.description() + " [TEST]",
                dataset.createdAt(),
                testExamples
        );

        // 2. Evaluate Baseline Matching Engine on VALIDATION Split
        BenchmarkRunResult valBaselineRun = benchmarkRunner.run(
                BenchmarkRunRequest.of(valDataset, candidatePoolProvider)
        );

        // 3. Extract Observations and Fit Platt Scaling Parameters
        List<CalibrationObservation> valObservations = valBaselineRun.exampleResults().stream()
                .filter(BenchmarkExampleResult::eligible)
                .filter(r -> r.predictedScore() != null)
                .map(CalibrationObservation::fromResult)
                .toList();

        if (valObservations.size() < 4) {
            throw new IllegalStateException("Insufficient evaluable observations in VALIDATION split: "
                    + valObservations.size());
        }

        PlattScalingModel fittedModel = parameterFitter.fit(
                valObservations,
                DEFAULT_MODEL_VERSION,
                "VALIDATION"
        );

        // 4. Threshold Optimization on VALIDATION Split
        ThresholdOptimizationReport thresholdReport = thresholdOptimizer.optimize(
                valObservations,
                fittedModel,
                targetPrecision,
                "VALIDATION"
        );

        // 5. Construct Calibrated Matching Engine
        TrackMatchingEngine calibratedEngine = fittedModel.createMatchingEngine(true);

        // 6. Evaluate on Held-Out TEST Split (Baseline vs Calibrated)
        BenchmarkRunResult testBaselineRun = benchmarkRunner.run(
                BenchmarkRunRequest.of(testDataset, candidatePoolProvider)
        );
        BenchmarkRunResult testCalibratedRun = benchmarkRunner.run(
                BenchmarkRunRequest.of(testDataset, calibratedEngine, candidatePoolProvider)
        );

        // 7. Calculate Comprehensive Metrics for TEST Split
        BenchmarkMetrics testBaselineMetrics = metricsCalculator.calculateAll(testBaselineRun.exampleResults());
        BenchmarkMetrics testCalibratedMetrics = metricsCalculator.calculateAll(testCalibratedRun.exampleResults());

        long totalDurationMs = System.currentTimeMillis() - startTime;

        return new BenchmarkCalibrationReport(
                fittedModel,
                thresholdReport,
                valBaselineRun,
                testBaselineRun,
                testCalibratedRun,
                testBaselineMetrics,
                testCalibratedMetrics,
                totalDurationMs
        );
    }
}
