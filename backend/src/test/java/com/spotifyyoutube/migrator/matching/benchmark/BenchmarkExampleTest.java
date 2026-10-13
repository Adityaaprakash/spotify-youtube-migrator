package com.spotifyyoutube.migrator.matching.benchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BenchmarkExample Contract & Invariant Tests")
class BenchmarkExampleTest {

    private final BenchmarkSourceTrack validSource = new BenchmarkSourceTrack(
            "spotify:track:4cOdK2wGLETKBW3PvgPWqT",
            "Never Gonna Give You Up",
            "Rick Astley",
            List.of("Rick Astley"),
            "Whenever You Need Somebody",
            213000L,
            false,
            "GBARL8700041"
    );

    private final BenchmarkTargetCandidate validTarget = new BenchmarkTargetCandidate(
            "dQw4w9WgXcQ",
            "Rick Astley - Never Gonna Give You Up (Official Music Video)",
            "Rick Astley",
            213000L,
            "UCuAXFkgsw1L7xaCfnd5JJOw",
            true
    );

    @Nested
    @DisplayName("Positive Match Invariants")
    class PositiveMatchInvariants {

        @Test
        @DisplayName("should construct valid verified positive match")
        void shouldConstructValidVerifiedPositiveMatch() {
            BenchmarkExample example = BenchmarkExample.verifiedMatch(
                    "BM-0001",
                    validSource,
                    validTarget,
                    "Official YouTube channel music video: dQw4w9WgXcQ",
                    BenchmarkDatasetSplit.DEV,
                    BenchmarkTrackCategory.OFFICIAL_MUSIC_VIDEO
            );

            assertThat(example.id()).isEqualTo("BM-0001");
            assertThat(example.isPositiveMatch()).isTrue();
            assertThat(example.isNoMatch()).isFalse();
            assertThat(example.isVerified()).isTrue();
            assertThat(example.expectedOutcome()).isEqualTo(BenchmarkExpectedOutcome.MATCH);
            assertThat(example.targetTrack()).isEqualTo(validTarget);
            assertThat(example.getTargetTrack()).contains(validTarget);
            assertThat(example.verificationEvidence()).isEqualTo("Official YouTube channel music video: dQw4w9WgXcQ");
            assertThat(example.split()).isEqualTo(BenchmarkDatasetSplit.DEV);
            assertThat(example.category()).isEqualTo(BenchmarkTrackCategory.OFFICIAL_MUSIC_VIDEO);
        }

        @Test
        @DisplayName("should fail when positive match has null target candidate")
        void shouldFailWhenPositiveMatchHasNullTarget() {
            assertThatThrownBy(() -> new BenchmarkExample(
                    "BM-0002",
                    BenchmarkExpectedOutcome.MATCH,
                    validSource,
                    null,
                    BenchmarkVerificationStatus.VERIFIED,
                    "Some evidence",
                    null,
                    BenchmarkDatasetSplit.DEV,
                    BenchmarkTrackCategory.STANDARD_POPULAR
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Target candidate must not be null for positive match example");
        }
    }

    @Nested
    @DisplayName("No-Match Invariants")
    class NoMatchInvariants {

        @Test
        @DisplayName("should construct valid verified no-match example")
        void shouldConstructValidVerifiedNoMatch() {
            BenchmarkExample example = BenchmarkExample.verifiedNoMatch(
                    "BM-0003",
                    validSource,
                    "Verified unavailable on YouTube; no official or user upload exists",
                    BenchmarkDatasetSplit.TEST,
                    BenchmarkTrackCategory.LEGITIMATE_NO_MATCH
            );

            assertThat(example.id()).isEqualTo("BM-0003");
            assertThat(example.isPositiveMatch()).isFalse();
            assertThat(example.isNoMatch()).isTrue();
            assertThat(example.isVerified()).isTrue();
            assertThat(example.expectedOutcome()).isEqualTo(BenchmarkExpectedOutcome.NO_MATCH);
            assertThat(example.targetTrack()).isNull();
            assertThat(example.getTargetTrack()).isEmpty();
            assertThat(example.split()).isEqualTo(BenchmarkDatasetSplit.TEST);
            assertThat(example.category()).isEqualTo(BenchmarkTrackCategory.LEGITIMATE_NO_MATCH);
        }

        @Test
        @DisplayName("should fail when no-match example carries a target candidate")
        void shouldFailWhenNoMatchHasTarget() {
            assertThatThrownBy(() -> new BenchmarkExample(
                    "BM-0004",
                    BenchmarkExpectedOutcome.NO_MATCH,
                    validSource,
                    validTarget,
                    BenchmarkVerificationStatus.VERIFIED,
                    "Evidence",
                    null,
                    BenchmarkDatasetSplit.DEV,
                    BenchmarkTrackCategory.LEGITIMATE_NO_MATCH
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Target candidate must be null for no-match example");
        }
    }

    @Nested
    @DisplayName("Verification Status Invariants")
    class VerificationStatusInvariants {

        @Test
        @DisplayName("should fail when verified status is missing evidence")
        void shouldFailWhenVerifiedMissingEvidence() {
            assertThatThrownBy(() -> new BenchmarkExample(
                    "BM-0005",
                    BenchmarkExpectedOutcome.MATCH,
                    validSource,
                    validTarget,
                    BenchmarkVerificationStatus.VERIFIED,
                    null,
                    null,
                    BenchmarkDatasetSplit.DEV,
                    BenchmarkTrackCategory.STANDARD_POPULAR
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Verification evidence must not be blank for VERIFIED example");

            assertThatThrownBy(() -> new BenchmarkExample(
                    "BM-0006",
                    BenchmarkExpectedOutcome.MATCH,
                    validSource,
                    validTarget,
                    BenchmarkVerificationStatus.VERIFIED,
                    "   ",
                    null,
                    BenchmarkDatasetSplit.DEV,
                    BenchmarkTrackCategory.STANDARD_POPULAR
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Verification evidence must not be blank for VERIFIED example");
        }

        @Test
        @DisplayName("should allow unverified status without evidence")
        void shouldAllowUnverifiedWithoutEvidence() {
            BenchmarkExample example = BenchmarkExample.unverifiedMatch(
                    "BM-0007",
                    validSource,
                    validTarget,
                    BenchmarkDatasetSplit.VALIDATION,
                    BenchmarkTrackCategory.STANDARD_POPULAR
            );

            assertThat(example.isVerified()).isFalse();
            assertThat(example.verificationStatus()).isEqualTo(BenchmarkVerificationStatus.UNVERIFIED);
            assertThat(example.verificationEvidence()).isNull();
            assertThat(example.getVerificationEvidence()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Validation and Required Fields")
    class ValidationAndRequiredFields {

        @Test
        @DisplayName("should fail when ID is null or blank")
        void shouldFailWhenIdIsInvalid() {
            assertThatThrownBy(() -> new BenchmarkExample(
                    null,
                    BenchmarkExpectedOutcome.MATCH,
                    validSource,
                    validTarget,
                    BenchmarkVerificationStatus.UNVERIFIED,
                    null,
                    null,
                    BenchmarkDatasetSplit.DEV,
                    BenchmarkTrackCategory.STANDARD_POPULAR
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("id must not be null");

            assertThatThrownBy(() -> new BenchmarkExample(
                    "   ",
                    BenchmarkExpectedOutcome.MATCH,
                    validSource,
                    validTarget,
                    BenchmarkVerificationStatus.UNVERIFIED,
                    null,
                    null,
                    BenchmarkDatasetSplit.DEV,
                    BenchmarkTrackCategory.STANDARD_POPULAR
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("id must not be blank");
        }

        @Test
        @DisplayName("should fail when sourceTrack is null")
        void shouldFailWhenSourceTrackIsNull() {
            assertThatThrownBy(() -> new BenchmarkExample(
                    "BM-0008",
                    BenchmarkExpectedOutcome.MATCH,
                    null,
                    validTarget,
                    BenchmarkVerificationStatus.UNVERIFIED,
                    null,
                    null,
                    BenchmarkDatasetSplit.DEV,
                    BenchmarkTrackCategory.STANDARD_POPULAR
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("sourceTrack must not be null");
        }

        @Test
        @DisplayName("should fail when outcome is null")
        void shouldFailWhenOutcomeIsNull() {
            assertThatThrownBy(() -> new BenchmarkExample(
                    "BM-0009",
                    null,
                    validSource,
                    validTarget,
                    BenchmarkVerificationStatus.UNVERIFIED,
                    null,
                    null,
                    BenchmarkDatasetSplit.DEV,
                    BenchmarkTrackCategory.STANDARD_POPULAR
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("expectedOutcome must not be null");
        }
    }
}
