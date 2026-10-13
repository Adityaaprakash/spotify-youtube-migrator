package com.spotifyyoutube.migrator.matching.benchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BenchmarkDatasetLoader Integration & Round-Trip Tests")
class BenchmarkDatasetLoaderTest {

    private final BenchmarkDatasetLoader loader = new BenchmarkDatasetLoader();

    @Nested
    @DisplayName("Default Ground-Truth Dataset Loading")
    class DefaultGroundTruthDatasetLoading {

        @Test
        @DisplayName("should load default ground_truth_benchmark.json successfully")
        void shouldLoadDefaultBenchmarkSuccessfully() {
            BenchmarkDataset dataset = loader.loadDefault();

            assertThat(dataset).isNotNull();
            assertThat(dataset.version()).isEqualTo("1.0.0");
            assertThat(dataset.description()).isNotBlank();
            assertThat(dataset.createdAt()).isNotBlank();
            assertThat(dataset.examples()).isNotEmpty();
        }

        @Test
        @DisplayName("should meet target count of at least 100 examples with verified ground truth")
        void shouldMeetTargetCount() {
            BenchmarkDataset dataset = loader.loadDefault();

            assertThat(dataset.examples()).hasSizeGreaterThanOrEqualTo(100);
            assertThat(dataset.examples()).hasSize(118);

            BenchmarkDatasetSummary summary = dataset.summary();
            assertThat(summary.totalExamples()).isEqualTo(118);
            assertThat(summary.matchCount()).isEqualTo(105);
            assertThat(summary.noMatchCount()).isEqualTo(13);
            assertThat(summary.verifiedCount()).isEqualTo(117);
            assertThat(summary.unverifiedCount()).isEqualTo(1);
            assertThat(summary.disputedCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("should contain examples distributed across all 3 splits")
        void shouldDistributeAcrossSplits() {
            BenchmarkDataset dataset = loader.loadDefault();
            BenchmarkDatasetSummary summary = dataset.summary();

            assertThat(summary.splitCounts().get(BenchmarkDatasetSplit.DEV)).isEqualTo(52);
            assertThat(summary.splitCounts().get(BenchmarkDatasetSplit.VALIDATION)).isEqualTo(36);
            assertThat(summary.splitCounts().get(BenchmarkDatasetSplit.TEST)).isEqualTo(30);
        }

        @Test
        @DisplayName("should contain case diversity across multiple typology categories")
        void shouldContainCaseDiversity() {
            BenchmarkDataset dataset = loader.loadDefault();
            BenchmarkDatasetSummary summary = dataset.summary();

            assertThat(summary.categoryCounts().get(BenchmarkTrackCategory.STANDARD_POPULAR))
                    .isGreaterThan(0);
            assertThat(summary.categoryCounts().get(BenchmarkTrackCategory.OFFICIAL_MUSIC_VIDEO))
                    .isGreaterThan(0);
            assertThat(summary.categoryCounts().get(BenchmarkTrackCategory.OFFICIAL_AUDIO))
                    .isGreaterThan(0);
            assertThat(summary.categoryCounts().get(BenchmarkTrackCategory.TOPIC_CHANNEL))
                    .isGreaterThan(0);
            assertThat(summary.categoryCounts().get(BenchmarkTrackCategory.FEATURED_ARTIST))
                    .isGreaterThan(0);
            assertThat(summary.categoryCounts().get(BenchmarkTrackCategory.REMIX))
                    .isGreaterThan(0);
            assertThat(summary.categoryCounts().get(BenchmarkTrackCategory.LIVE))
                    .isGreaterThan(0);
            assertThat(summary.categoryCounts().get(BenchmarkTrackCategory.ACOUSTIC))
                    .isGreaterThan(0);
            assertThat(summary.categoryCounts().get(BenchmarkTrackCategory.COVER))
                    .isGreaterThan(0);
            assertThat(summary.categoryCounts().get(BenchmarkTrackCategory.REMASTER))
                    .isGreaterThan(0);
            assertThat(summary.categoryCounts().get(BenchmarkTrackCategory.LEGITIMATE_NO_MATCH))
                    .isEqualTo(13);
        }

        @Test
        @DisplayName("every record in default dataset should pass strict invariant assertions")
        void everyRecordShouldPassInvariants() {
            BenchmarkDataset dataset = loader.loadDefault();

            for (BenchmarkExample example : dataset.examples()) {
                assertThat(example.id()).isNotBlank();
                assertThat(example.sourceTrack()).isNotNull();
                assertThat(example.sourceTrack().title()).isNotBlank();
                assertThat(example.sourceTrack().primaryArtist()).isNotBlank();

                if (example.expectedOutcome().isMatch()) {
                    assertThat(example.targetTrack()).isNotNull();
                    assertThat(example.targetTrack().videoId()).isNotBlank();
                } else {
                    assertThat(example.targetTrack()).isNull();
                }

                if (example.verificationStatus().isVerified()) {
                    assertThat(example.verificationEvidence()).isNotBlank();
                }
            }
        }
    }

    @Nested
    @DisplayName("Serialization and Round-Trip")
    class SerializationAndRoundTrip {

        @Test
        @DisplayName("should serialize and deserialize preserving dataset contents")
        void shouldRoundTripDataset() {
            BenchmarkSourceTrack source = new BenchmarkSourceTrack(
                    "spotify:track:rt1",
                    "Roundtrip Title",
                    "Roundtrip Artist",
                    List.of("Roundtrip Artist", "Guest"),
                    "Roundtrip Album",
                    195000L,
                    true,
                    "US1234567890"
            );

            BenchmarkTargetCandidate target = new BenchmarkTargetCandidate(
                    "abcdefghijk",
                    "Roundtrip Artist - Roundtrip Title ft. Guest",
                    "Roundtrip Channel",
                    195000L,
                    "channel123",
                    true
            );

            BenchmarkExample example = BenchmarkExample.verifiedMatch(
                    "BM-9999",
                    source,
                    target,
                    "Verified roundtrip record",
                    BenchmarkDatasetSplit.DEV,
                    BenchmarkTrackCategory.FEATURED_ARTIST
            );

            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0",
                    "Roundtrip Test Dataset",
                    "2026-10-13T00:00:00Z",
                    List.of(example)
            );

            String json = loader.toJson(dataset);
            assertThat(json).contains("\"version\" : \"1.0.0\"");
            assertThat(json).contains("\"id\" : \"BM-9999\"");
            assertThat(json).contains("\"videoId\" : \"abcdefghijk\"");

            BenchmarkDataset restored = loader.loadFromJson(json);
            assertThat(restored.version()).isEqualTo(dataset.version());
            assertThat(restored.examples()).hasSize(1);

            BenchmarkExample restoredEx = restored.examples().get(0);
            assertThat(restoredEx.id()).isEqualTo("BM-9999");
            assertThat(restoredEx.expectedOutcome()).isEqualTo(BenchmarkExpectedOutcome.MATCH);
            assertThat(restoredEx.sourceTrack().title()).isEqualTo("Roundtrip Title");
            assertThat(restoredEx.sourceTrack().artists()).containsExactly("Roundtrip Artist", "Guest");
            assertThat(restoredEx.targetTrack().videoId()).isEqualTo("abcdefghijk");
            assertThat(restoredEx.split()).isEqualTo(BenchmarkDatasetSplit.DEV);
            assertThat(restoredEx.category()).isEqualTo(BenchmarkTrackCategory.FEATURED_ARTIST);
        }

        @Test
        @DisplayName("should fail on non-existent classpath resource")
        void shouldFailOnNonExistentClasspathResource() {
            assertThatThrownBy(() -> loader.loadFromClasspath("/benchmark/non_existent.json"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Benchmark dataset resource not found on classpath");
        }

        @Test
        @DisplayName("should fail on malformed JSON")
        void shouldFailOnMalformedJson() {
            assertThatThrownBy(() -> loader.loadFromJson("{\"version\": \"1.0.0\", invalid json"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to parse benchmark dataset from JSON string");
        }

        @Test
        @DisplayName("should fail validation on semantically invalid dataset JSON")
        void shouldFailValidationOnSemanticallyInvalidDatasetJson() {
            String invalidJson = """
                    {
                      "version": "not-semver",
                      "description": "Invalid dataset",
                      "createdAt": "2026-10-13T00:00:00Z",
                      "examples": []
                    }
                    """;

            assertThatThrownBy(() -> loader.loadFromJson(invalidJson))
                    .isInstanceOf(BenchmarkValidationException.class)
                    .hasMessageContaining("Benchmark dataset validation failed");
        }
    }
}
