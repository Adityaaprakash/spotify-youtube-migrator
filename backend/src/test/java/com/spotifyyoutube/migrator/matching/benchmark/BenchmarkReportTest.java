package com.spotifyyoutube.migrator.matching.benchmark;

import com.spotifyyoutube.migrator.matching.benchmark.pool.ControlledDecoyCandidatePoolProvider;
import com.spotifyyoutube.migrator.matching.benchmark.pool.FixtureCandidatePoolProvider;
import com.spotifyyoutube.migrator.matching.benchmark.report.BenchmarkEvaluationReport;
import com.spotifyyoutube.migrator.matching.benchmark.report.BenchmarkReportFormatter;
import com.spotifyyoutube.migrator.matching.benchmark.report.BenchmarkReportGenerator;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkRunRequest;
import com.spotifyyoutube.migrator.matching.benchmark.runner.BenchmarkRunResult;
import com.spotifyyoutube.migrator.matching.benchmark.runner.DefaultBenchmarkRunner;
import com.spotifyyoutube.migrator.migration.application.destination.model.DestinationTrackCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Benchmark Report Generation & Formatting Tests")
class BenchmarkReportTest {

    private final BenchmarkReportGenerator generator = new BenchmarkReportGenerator();
    private final BenchmarkReportFormatter formatter = new BenchmarkReportFormatter();

    @Test
    @DisplayName("should generate and format complete report with all sections and limitations")
    void shouldGenerateAndFormatCompleteReport() {
        BenchmarkSourceTrack source1 = BenchmarkSourceTrack.of(
                "spotify:track:1", "Song A", "Artist A", "Album A", 200000L
        );
        BenchmarkTargetCandidate target1 = BenchmarkTargetCandidate.of(
                "vidA1234567", "Artist A - Song A", "Artist A", 200000L, true
        );
        BenchmarkExample ex1 = BenchmarkExample.verifiedMatch(
                "BM-0001", source1, target1, "Evidence for verified match",
                BenchmarkDatasetSplit.DEV, BenchmarkTrackCategory.STANDARD_POPULAR
        );

        BenchmarkSourceTrack source2 = BenchmarkSourceTrack.of(
                "spotify:track:2", "Unreleased B", "Artist B", "Album B", 180000L
        );
        BenchmarkExample ex2 = BenchmarkExample.verifiedNoMatch(
                "BM-0002", source2, "Evidence for verified no match",
                BenchmarkDatasetSplit.TEST, BenchmarkTrackCategory.LEGITIMATE_NO_MATCH
        );

        BenchmarkDataset dataset = new BenchmarkDataset(
                "1.0.0", "Report Test Dataset", "2026-10-14", List.of(ex1, ex2)
        );

        DestinationTrackCandidate cand1 = new DestinationTrackCandidate(
                "vidA1234567", "Song A", "Artist A", "Provided to YouTube by Label",
                null, null, Duration.ofMillis(200000)
        );

        FixtureCandidatePoolProvider poolProvider = new FixtureCandidatePoolProvider(
                Map.of("BM-0001", List.of(cand1))
        );

        DefaultBenchmarkRunner runner = new DefaultBenchmarkRunner();
        BenchmarkRunResult runResult = runner.run(dataset, poolProvider);

        BenchmarkEvaluationReport report = generator.generateReport(runResult);

        assertThat(report.totalExamples()).isEqualTo(2);
        assertThat(report.eligibleExamples()).isEqualTo(2);
        assertThat(report.excludedExamples()).isEqualTo(0);
        assertThat(report.failedExamples()).isEqualTo(0);
        assertThat(report.eligibleExamples() + report.excludedExamples() + report.failedExamples())
                .isEqualTo(report.totalExamples());
        assertThat(report.datasetVersion()).isEqualTo("1.0.0");

        // Format to Markdown
        String markdown = formatter.formatToMarkdown(report);
        assertThat(markdown).contains("# Music Matching Benchmark Evaluation Report");
        assertThat(markdown).contains("## 1. Dataset Scope & Eligibility");
        assertThat(markdown).contains("## 2. Candidate Ranking Performance");
        assertThat(markdown).contains("## 3. Decision Policy Performance");
        assertThat(markdown).contains("### Decision Confusion Matrix");
        assertThat(markdown).contains("## 4. Probability Calibration & High-Confidence Reliability");
        assertThat(markdown).contains("## 5. Performance by Dataset Split");
        assertThat(markdown).contains("## 6. Evaluation Discipline & Limitations");

        // Format to plain text
        String plainText = formatter.formatToPlainText(report);
        assertThat(plainText).contains("BENCHMARK EVALUATION REPORT");
        assertThat(plainText).contains("Total Examples: 2");
    }

    @Test
    @DisplayName("should emit synthetic candidate pool limitations note when using decoy provider")
    void shouldEmitSyntheticDecoyLimitation() {
        BenchmarkSourceTrack source = BenchmarkSourceTrack.of(
                "spotify:track:1", "Song A", "Artist A", "Album A", 200000L
        );
        BenchmarkTargetCandidate target = BenchmarkTargetCandidate.of(
                "vidA1234567", "Artist A - Song A", "Artist A", 200000L, true
        );
        BenchmarkExample ex = BenchmarkExample.verifiedMatch(
                "BM-0001", source, target, "Evidence for verified match",
                BenchmarkDatasetSplit.DEV, BenchmarkTrackCategory.STANDARD_POPULAR
        );

        BenchmarkDataset dataset = new BenchmarkDataset(
                "1.0.0", "Test Dataset", "2026-10-14", List.of(ex)
        );

        DefaultBenchmarkRunner runner = new DefaultBenchmarkRunner();
        BenchmarkRunResult runResult = runner.run(BenchmarkRunRequest.of(dataset, new ControlledDecoyCandidatePoolProvider()));

        BenchmarkEvaluationReport report = generator.generateReport(runResult);

        assertThat(report.limitations())
                .anyMatch(lim -> lim.contains("controlled synthetic decoys"));
    }
}
