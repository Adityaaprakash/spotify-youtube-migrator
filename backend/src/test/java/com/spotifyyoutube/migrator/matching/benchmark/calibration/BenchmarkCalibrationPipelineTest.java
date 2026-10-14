package com.spotifyyoutube.migrator.matching.benchmark.calibration;

import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDataset;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDatasetLoader;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkDatasetSplit;
import com.spotifyyoutube.migrator.matching.benchmark.BenchmarkExample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BenchmarkCalibrationPipeline")
class BenchmarkCalibrationPipelineTest {

    private final BenchmarkCalibrationPipeline pipeline = new BenchmarkCalibrationPipeline();

    @Nested
    @DisplayName("End-to-end execution on default ground-truth dataset")
    class EndToEndTests {

        @Test
        @DisplayName("should execute full calibration pipeline and verify held-out TEST split calibration")
        void shouldExecutePipelineOnDefaultDataset() {
            BenchmarkCalibrationReport report = pipeline.runDefault();

            assertThat(report).isNotNull();

            // 1. Check fitted Platt scaling model derived from VALIDATION split
            PlattScalingModel model = report.fittedModel();
            assertThat(model.slopeA()).isGreaterThan(0.0);
            assertThat(model.interceptB()).isFinite();
            assertThat(model.trainingSplit()).isEqualTo("VALIDATION");
            assertThat(model.sampleCount()).isGreaterThanOrEqualTo(4);
            assertThat(model.positiveCount()).isGreaterThan(0);
            assertThat(model.negativeCount()).isGreaterThan(0);

            // 2. Check threshold optimization report
            ThresholdOptimizationReport threshReport = report.thresholdOptimizationReport();
            assertThat(threshReport.evaluationSplit()).isEqualTo("VALIDATION");
            assertThat(threshReport.optimalScorePoint()).isNotNull();
            assertThat(threshReport.optimalProbabilityPoint()).isNotNull();

            // 3. Check baseline vs calibrated TEST split results
            assertThat(report.testBaselineRun()).isNotNull();
            assertThat(report.testCalibratedRun()).isNotNull();
            assertThat(report.testCalibratedRun().exampleResults()).isNotEmpty();

            // 4. Critical scientific verification: TEST split calibration metrics MUST be available
            var calMetrics = report.testCalibratedMetrics().calibration();
            assertThat(calMetrics.available())
                    .as("Calibrated metrics must be available when calibrated engine is used")
                    .isTrue();
            assertThat(calMetrics.brierScore()).isGreaterThanOrEqualTo(0.0);
            assertThat(calMetrics.expectedCalibrationError()).isGreaterThanOrEqualTo(0.0);
            assertThat(calMetrics.buckets()).isNotEmpty();

            // Baseline TEST split calibration metrics MUST remain unavailable
            assertThat(report.testBaselineMetrics().calibration().available())
                    .as("Baseline uncalibrated metrics must honestly remain unavailable")
                    .isFalse();

            // 5. Safety audit: Zero incorrect matches at high confidence
            var highConf = report.testCalibratedMetrics().highConfidence();
            assertThat(highConf.available()).isTrue();
            assertThat(highConf.highConfidenceIncorrectCount())
                    .as("High-confidence incorrect count must be 0 for safe automation")
                    .isZero();

            // 6. Markdown report generation
            String markdown = report.formatToMarkdown();
            assertThat(markdown).contains("# Phase 8E: Empirical Probability Calibration & Threshold Optimization Report");
            assertThat(markdown).contains("## 1. Fitted Platt Scaling Model Parameters");
            assertThat(markdown).contains("## 2. Decision Threshold Optimization");
            assertThat(markdown).contains("## 3. Held-Out TEST Split Calibration & Verification");
            assertThat(markdown).contains("### High-Confidence Safety Audit");
            assertThat(markdown).contains("## 4. Scientific Limitations & Data Provenance");
        }
    }

    @Nested
    @DisplayName("Validation and error handling")
    class ValidationTests {

        @Test
        @DisplayName("should fail when dataset has no VALIDATION split")
        void shouldFailWhenNoValidationSplit() {
            BenchmarkDataset full = new BenchmarkDatasetLoader().loadDefault();
            List<BenchmarkExample> noVal = full.examples().stream()
                    .filter(e -> e.split() != BenchmarkDatasetSplit.VALIDATION)
                    .toList();

            BenchmarkDataset dataset = new BenchmarkDataset(
                    full.version(),
                    "No validation",
                    full.createdAt(),
                    noVal
            );

            assertThatThrownBy(() -> pipeline.runCalibration(dataset))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("VALIDATION");
        }

        @Test
        @DisplayName("should fail when dataset has no TEST split")
        void shouldFailWhenNoTestSplit() {
            BenchmarkDataset full = new BenchmarkDatasetLoader().loadDefault();
            List<BenchmarkExample> noTest = full.examples().stream()
                    .filter(e -> e.split() != BenchmarkDatasetSplit.TEST)
                    .toList();

            BenchmarkDataset dataset = new BenchmarkDataset(
                    full.version(),
                    "No test",
                    full.createdAt(),
                    noTest
            );

            assertThatThrownBy(() -> pipeline.runCalibration(dataset))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TEST");
        }
    }
}
