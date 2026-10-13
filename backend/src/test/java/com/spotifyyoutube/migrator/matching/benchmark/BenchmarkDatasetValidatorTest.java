package com.spotifyyoutube.migrator.matching.benchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BenchmarkDatasetValidator Contract & Integrity Tests")
class BenchmarkDatasetValidatorTest {

    private final BenchmarkDatasetValidator validator = new BenchmarkDatasetValidator();

    private final BenchmarkSourceTrack source1 = new BenchmarkSourceTrack(
            "spotify:track:111",
            "Blinding Lights",
            "The Weeknd",
            List.of("The Weeknd"),
            "After Hours",
            200000L,
            false,
            null
    );

    private final BenchmarkTargetCandidate target1 = new BenchmarkTargetCandidate(
            "4NRXx6U8ABQ",
            "The Weeknd - Blinding Lights (Official Video)",
            "The Weeknd",
            200000L,
            "UC0WP5P-ufpRfjbNrmOWwLBQ",
            true
    );

    private final BenchmarkExample example1 = BenchmarkExample.verifiedMatch(
            "BM-0001",
            source1,
            target1,
            "Official Vevo / artist upload",
            BenchmarkDatasetSplit.DEV,
            BenchmarkTrackCategory.STANDARD_POPULAR
    );

    @Nested
    @DisplayName("Metadata Validation")
    class MetadataValidation {

        @Test
        @DisplayName("should accept dataset with valid semver version and non-blank metadata")
        void shouldAcceptValidMetadata() {
            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0",
                    "A valid benchmark dataset",
                    "2026-10-13T00:00:00Z",
                    List.of(example1)
            );

            BenchmarkValidationResult result = validator.validate(dataset);

            assertThat(result.isValid()).isTrue();
            assertThat(result.errors()).isEmpty();
        }

        @Test
        @DisplayName("should reject null dataset")
        void shouldRejectNullDataset() {
            BenchmarkValidationResult result = validator.validate(null);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).contains("Benchmark dataset must not be null");
        }

        @Test
        @DisplayName("should reject invalid semver version")
        void shouldRejectInvalidSemver() {
            BenchmarkDataset dataset = new BenchmarkDataset(
                    "v1_beta",
                    "Invalid semver dataset",
                    "2026-10-13T00:00:00Z",
                    List.of(example1)
            );

            BenchmarkValidationResult result = validator.validate(dataset);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors())
                    .anyMatch(err -> err.contains("Dataset version must follow semantic versioning"));
        }

        @Test
        @DisplayName("should reject empty examples list")
        void shouldRejectEmptyExamples() {
            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0",
                    "Empty dataset",
                    "2026-10-13T00:00:00Z",
                    List.of()
            );

            BenchmarkValidationResult result = validator.validate(dataset);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors())
                    .contains("Dataset must contain at least one benchmark example");
        }
    }

    @Nested
    @DisplayName("Referential Integrity and Duplication Checks")
    class ReferentialIntegrityAndDuplicationChecks {

        @Test
        @DisplayName("should reject duplicate benchmark example IDs with explicit ID in message")
        void shouldRejectDuplicateExampleIds() {
            BenchmarkExample duplicateExample = new BenchmarkExample(
                    "BM-0001",
                    BenchmarkExpectedOutcome.MATCH,
                    source1,
                    target1,
                    BenchmarkVerificationStatus.UNVERIFIED,
                    null,
                    null,
                    BenchmarkDatasetSplit.VALIDATION,
                    BenchmarkTrackCategory.STANDARD_POPULAR
            );

            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0",
                    "Duplicate IDs dataset",
                    "2026-10-13T00:00:00Z",
                    List.of(example1, duplicateExample)
            );

            BenchmarkValidationResult result = validator.validate(dataset);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors())
                    .contains("Duplicate benchmark example ID found: 'BM-0001'");
        }

        @Test
        @DisplayName("should warn when duplicate Spotify source ID is present across multiple examples")
        void shouldWarnOnDuplicateSourceId() {
            BenchmarkExample example2 = BenchmarkExample.verifiedMatch(
                    "BM-0002",
                    source1,
                    target1,
                    "Official video repeat test",
                    BenchmarkDatasetSplit.VALIDATION,
                    BenchmarkTrackCategory.STANDARD_POPULAR
            );

            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0",
                    "Duplicate Source ID dataset",
                    "2026-10-13T00:00:00Z",
                    List.of(example1, example2)
            );

            BenchmarkValidationResult result = validator.validate(dataset);

            assertThat(result.isValid()).isTrue();
            assertThat(result.warnings())
                    .anyMatch(w -> w.contains("Duplicate source track ID 'spotify:track:111'"));
        }

        @Test
        @DisplayName("should warn when same artist and title appears multiple times within same split")
        void shouldWarnOnDuplicateArtistTitleInSameSplit() {
            BenchmarkSourceTrack sourceDiffId = new BenchmarkSourceTrack(
                    "spotify:track:222",
                    "Blinding Lights",
                    "The Weeknd",
                    List.of("The Weeknd"),
                    "After Hours Deluxe",
                    200000L,
                    false,
                    null
            );

            BenchmarkExample example2 = BenchmarkExample.verifiedMatch(
                    "BM-0002",
                    sourceDiffId,
                    target1,
                    "Deluxe edition match",
                    BenchmarkDatasetSplit.DEV,
                    BenchmarkTrackCategory.STANDARD_POPULAR
            );

            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0",
                    "Same Split Collision dataset",
                    "2026-10-13T00:00:00Z",
                    List.of(example1, example2)
            );

            BenchmarkValidationResult result = validator.validate(dataset);

            assertThat(result.isValid()).isTrue();
            assertThat(result.warnings())
                    .anyMatch(w -> w.contains("Same artist and title appeared multiple times in split DEV"));
        }

        @Test
        @DisplayName("should warn when YouTube video ID does not follow 11-char pattern")
        void shouldWarnOnNonStandardVideoIdPattern() {
            BenchmarkTargetCandidate nonStandardTarget = new BenchmarkTargetCandidate(
                    "shortId",
                    "Title",
                    "Channel",
                    180000L,
                    "channelId",
                    true
            );

            BenchmarkExample nonStandardExample = BenchmarkExample.verifiedMatch(
                    "BM-0002",
                    source1,
                    nonStandardTarget,
                    "Short id test",
                    BenchmarkDatasetSplit.DEV,
                    BenchmarkTrackCategory.STANDARD_POPULAR
            );

            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0",
                    "Pattern warning dataset",
                    "2026-10-13T00:00:00Z",
                    List.of(nonStandardExample)
            );

            BenchmarkValidationResult result = validator.validate(dataset);

            assertThat(result.isValid()).isTrue();
            assertThat(result.warnings())
                    .anyMatch(w -> w.contains("Target videoId 'shortId' does not match standard 11-char pattern"));
        }

        @Test
        @DisplayName("should reject verified example with trivial short verification evidence")
        void shouldRejectVerifiedExampleWithTrivialShortEvidence() {
            BenchmarkExample weakEvidenceExample = new BenchmarkExample(
                    "BM-0002",
                    BenchmarkExpectedOutcome.MATCH,
                    source1,
                    target1,
                    BenchmarkVerificationStatus.VERIFIED,
                    "too short",
                    null,
                    BenchmarkDatasetSplit.DEV,
                    BenchmarkTrackCategory.STANDARD_POPULAR
            );

            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0",
                    "Weak evidence dataset",
                    "2026-10-13T00:00:00Z",
                    List.of(weakEvidenceExample)
            );

            BenchmarkValidationResult result = validator.validate(dataset);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors())
                    .anyMatch(err -> err.contains("meaningful verificationEvidence (at least 10 chars)"));
        }
    }

    @Nested
    @DisplayName("validateAndThrow Behavior")
    class ValidateAndThrowBehavior {

        @Test
        @DisplayName("should not throw when dataset is valid")
        void shouldNotThrowWhenValid() {
            BenchmarkDataset dataset = new BenchmarkDataset(
                    "1.0.0",
                    "Valid dataset",
                    "2026-10-13T00:00:00Z",
                    List.of(example1)
            );

            validator.validateAndThrow(dataset);
        }

        @Test
        @DisplayName("should throw BenchmarkValidationException with itemized errors when dataset is invalid")
        void shouldThrowWhenInvalid() {
            BenchmarkDataset invalidDataset = new BenchmarkDataset(
                    "bad_version",
                    "",
                    "2026-10-13",
                    List.of()
            );

            assertThatThrownBy(() -> validator.validateAndThrow(invalidDataset))
                    .isInstanceOf(BenchmarkValidationException.class)
                    .satisfies(ex -> {
                        BenchmarkValidationException bve = (BenchmarkValidationException) ex;
                        assertThat(bve.errors()).isNotEmpty();
                        assertThat(bve.getMessage()).contains("Benchmark dataset validation failed");
                    });
        }
    }
}
