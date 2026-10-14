package com.spotifyyoutube.migrator.matching.benchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BenchmarkDataset Contract & Query Tests")
class BenchmarkDatasetTest {

    private final BenchmarkSourceTrack source1 = new BenchmarkSourceTrack(
            "spotify:track:1",
            "Song One",
            "Artist A",
            List.of("Artist A"),
            "Album X",
            180000L,
            false,
            null
    );

    private final BenchmarkTargetCandidate target1 = new BenchmarkTargetCandidate(
            "vid11111111",
            "Artist A - Song One (Official)",
            "Artist A",
            180000L,
            "channel1",
            true
    );

    private final BenchmarkSourceTrack source2 = new BenchmarkSourceTrack(
            "spotify:track:2",
            "Song Two",
            "Artist B",
            List.of("Artist B"),
            "Album Y",
            210000L,
            false,
            null
    );

    private final BenchmarkExample example1 = BenchmarkExample.verifiedMatch(
            "BM-0001",
            source1,
            target1,
            "Official video on official channel",
            BenchmarkDatasetSplit.DEV,
            BenchmarkTrackCategory.STANDARD_POPULAR
    );

    private final BenchmarkExample example2 = BenchmarkExample.verifiedNoMatch(
            "BM-0002",
            source2,
            "No upload available on YouTube",
            BenchmarkDatasetSplit.TEST,
            BenchmarkTrackCategory.LEGITIMATE_NO_MATCH
    );

    private final BenchmarkExample example3 = BenchmarkExample.unverifiedMatch(
            "BM-0003",
            source1,
            target1,
            BenchmarkDatasetSplit.DEV,
            BenchmarkTrackCategory.LIVE
    );

    @Nested
    @DisplayName("Dataset Construction")
    class DatasetConstruction {

        @Test
        @DisplayName("should construct valid dataset with immutable examples list")
        void shouldConstructValidDataset() {
            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0",
                    "Test Benchmark Dataset",
                    "2026-10-13T00:00:00Z",
                    List.of(example1, example2)
            );

            assertThat(dataset.version()).isEqualTo("1.0.0");
            assertThat(dataset.description()).isEqualTo("Test Benchmark Dataset");
            assertThat(dataset.createdAt()).isEqualTo("2026-10-13T00:00:00Z");
            assertThat(dataset.examples()).hasSize(2);

            assertThatThrownBy(() -> dataset.examples().add(example3))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("should reject null fields in constructor")
        void shouldRejectNullFields() {
            assertThatThrownBy(() -> new BenchmarkDataset(null, "desc", "2026-10-13", List.of(example1)))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("version");

            assertThatThrownBy(() -> new BenchmarkDataset("1.0.0", null, "2026-10-13", List.of(example1)))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("description");

            assertThatThrownBy(() -> new BenchmarkDataset("1.0.0", "desc", null, List.of(example1)))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("createdAt");

            assertThatThrownBy(() -> new BenchmarkDataset("1.0.0", "desc", "2026-10-13", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("examples");
        }

        @Test
        @DisplayName("should allow empty examples list in constructor")
        void shouldAllowEmptyExamplesListInConstructor() {
            BenchmarkDataset emptyDataset = new BenchmarkDataset("1.0.0", "desc", "2026-10-13", List.of());
            assertThat(emptyDataset.examples()).isEmpty();
            assertThat(emptyDataset.size()).isZero();
        }
    }

    @Nested
    @DisplayName("Query and Filter Utilities")
    class QueryAndFilterUtilities {

        private final BenchmarkDataset dataset = new BenchmarkDataset(
                "1.0.0",
                "Query Test Dataset",
                "2026-10-13T00:00:00Z",
                List.of(example1, example2, example3)
        );

        @Test
        @DisplayName("should find example by ID")
        void shouldFindExampleById() {
            assertThat(dataset.getExampleById("BM-0001")).contains(example1);
            assertThat(dataset.getExampleById("BM-0002")).contains(example2);
            assertThat(dataset.getExampleById("BM-9999")).isEmpty();
        }

        @Test
        @DisplayName("should filter examples by dataset split")
        void shouldFilterBySplit() {
            List<BenchmarkExample> devExamples = dataset.getExamplesBySplit(BenchmarkDatasetSplit.DEV);
            assertThat(devExamples).containsExactly(example1, example3);

            List<BenchmarkExample> testExamples = dataset.getExamplesBySplit(BenchmarkDatasetSplit.TEST);
            assertThat(testExamples).containsExactly(example2);

            List<BenchmarkExample> valExamples = dataset.getExamplesBySplit(BenchmarkDatasetSplit.VALIDATION);
            assertThat(valExamples).isEmpty();
        }

        @Test
        @DisplayName("should filter examples by expected outcome")
        void shouldFilterByOutcome() {
            List<BenchmarkExample> matches = dataset.getExamplesByOutcome(BenchmarkExpectedOutcome.MATCH);
            assertThat(matches).containsExactly(example1, example3);

            List<BenchmarkExample> noMatches = dataset.getExamplesByOutcome(BenchmarkExpectedOutcome.NO_MATCH);
            assertThat(noMatches).containsExactly(example2);
        }

        @Test
        @DisplayName("should filter examples by verification status")
        void shouldFilterByStatus() {
            List<BenchmarkExample> verified = dataset.getExamplesByStatus(BenchmarkVerificationStatus.VERIFIED);
            assertThat(verified).containsExactly(example1, example2);

            List<BenchmarkExample> unverified = dataset.getExamplesByStatus(BenchmarkVerificationStatus.UNVERIFIED);
            assertThat(unverified).containsExactly(example3);

            List<BenchmarkExample> disputed = dataset.getExamplesByStatus(BenchmarkVerificationStatus.DISPUTED);
            assertThat(disputed).isEmpty();
        }

        @Test
        @DisplayName("should filter examples by track category")
        void shouldFilterByCategory() {
            List<BenchmarkExample> standard = dataset.getExamplesByCategory(
                    BenchmarkTrackCategory.STANDARD_POPULAR
            );
            assertThat(standard).containsExactly(example1);

            List<BenchmarkExample> legitimateNoMatch = dataset.getExamplesByCategory(
                    BenchmarkTrackCategory.LEGITIMATE_NO_MATCH
            );
            assertThat(legitimateNoMatch).containsExactly(example2);

            List<BenchmarkExample> live = dataset.getExamplesByCategory(
                    BenchmarkTrackCategory.LIVE
            );
            assertThat(live).containsExactly(example3);
        }
    }

    @Nested
    @DisplayName("Dataset Summary Calculation")
    class DatasetSummaryCalculation {

        @Test
        @DisplayName("should accurately aggregate summary metrics across splits and categories")
        void shouldAccuratelyAggregateSummaryMetrics() {
            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0",
                    "Summary Test Dataset",
                    "2026-10-13T00:00:00Z",
                    List.of(example1, example2, example3)
            );

            BenchmarkDatasetSummary summary = dataset.summary();

            assertThat(summary.totalExamples()).isEqualTo(3);
            assertThat(summary.verifiedCount()).isEqualTo(2);
            assertThat(summary.unverifiedCount()).isEqualTo(1);
            assertThat(summary.disputedCount()).isEqualTo(0);
            assertThat(summary.matchCount()).isEqualTo(2);
            assertThat(summary.noMatchCount()).isEqualTo(1);

            assertThat(summary.splitCounts()).containsEntry(BenchmarkDatasetSplit.DEV, 2);
            assertThat(summary.splitCounts()).containsEntry(BenchmarkDatasetSplit.TEST, 1);
            assertThat(summary.splitCounts()).containsEntry(BenchmarkDatasetSplit.VALIDATION, 0);

            assertThat(summary.categoryCounts()).containsEntry(BenchmarkTrackCategory.STANDARD_POPULAR, 1);
            assertThat(summary.categoryCounts()).containsEntry(BenchmarkTrackCategory.LEGITIMATE_NO_MATCH, 1);
            assertThat(summary.categoryCounts()).containsEntry(BenchmarkTrackCategory.LIVE, 1);
        }
    }
}
