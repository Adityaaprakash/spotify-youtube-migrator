package com.spotifyyoutube.migrator.matching.benchmark;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable record representing a single ground-truth benchmark example.
 * <p>
 * Links a source Spotify track to its expected matching outcome, verified destination
 * target (if positive match), audit provenance, and evaluation slice metadata.
 *
 * @param id non-null, unique stable identifier (e.g. "BM-0001")
 * @param expectedOutcome non-null expected migration match decision (MATCH or NO_MATCH)
 * @param sourceTrack non-null source Spotify track representation
 * @param targetTrack expected target YouTube candidate (required when MATCH, null when NO_MATCH)
 * @param verificationStatus non-null audit verification status (VERIFIED, UNVERIFIED, DISPUTED)
 * @param verificationEvidence traceable evidence reference (required when VERIFIED)
 * @param notes optional annotator notes or edge-case caveats
 * @param split non-null dataset split assignment (DEV, VALIDATION, TEST)
 * @param category non-null typology category classification
 */
public record BenchmarkExample(
        String id,
        BenchmarkExpectedOutcome expectedOutcome,
        BenchmarkSourceTrack sourceTrack,
        BenchmarkTargetCandidate targetTrack,
        BenchmarkVerificationStatus verificationStatus,
        String verificationEvidence,
        String notes,
        BenchmarkDatasetSplit split,
        BenchmarkTrackCategory category
) {

    public BenchmarkExample {
        Objects.requireNonNull(id, "id must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        id = id.trim();
        Objects.requireNonNull(expectedOutcome, "expectedOutcome must not be null");
        Objects.requireNonNull(sourceTrack, "sourceTrack must not be null");
        Objects.requireNonNull(verificationStatus, "verificationStatus must not be null");
        Objects.requireNonNull(split, "split must not be null");
        Objects.requireNonNull(category, "category must not be null");

        if (expectedOutcome.isMatch() && targetTrack == null) {
            throw new IllegalArgumentException(
                    "Target candidate must not be null for positive match example: " + id
            );
        }

        if (expectedOutcome.isNoMatch() && targetTrack != null) {
            throw new IllegalArgumentException(
                    "Target candidate must be null for no-match example: " + id
            );
        }

        if (verificationStatus.isVerified()) {
            if (verificationEvidence == null || verificationEvidence.isBlank()) {
                throw new IllegalArgumentException(
                        "Verification evidence must not be blank for VERIFIED example: " + id
                );
            }
        }

        verificationEvidence = verificationEvidence != null ? verificationEvidence.trim() : null;
        notes = notes != null ? notes.trim() : null;
    }

    /**
     * Factory for creating a verified positive match benchmark example.
     *
     * @param id unique benchmark ID
     * @param sourceTrack source track metadata
     * @param targetTrack target YouTube candidate
     * @param evidence audit evidence reference
     * @param split dataset split
     * @param category category tag
     * @return constructed BenchmarkExample
     */
    public static BenchmarkExample verifiedMatch(
            String id,
            BenchmarkSourceTrack sourceTrack,
            BenchmarkTargetCandidate targetTrack,
            String evidence,
            BenchmarkDatasetSplit split,
            BenchmarkTrackCategory category
    ) {
        return new BenchmarkExample(
                id,
                BenchmarkExpectedOutcome.MATCH,
                sourceTrack,
                targetTrack,
                BenchmarkVerificationStatus.VERIFIED,
                evidence,
                null,
                split,
                category
        );
    }

    /**
     * Factory for creating a verified legitimate no-match benchmark example.
     *
     * @param id unique benchmark ID
     * @param sourceTrack source track metadata
     * @param evidence audit evidence reference
     * @param split dataset split
     * @param category category tag
     * @return constructed BenchmarkExample
     */
    public static BenchmarkExample verifiedNoMatch(
            String id,
            BenchmarkSourceTrack sourceTrack,
            String evidence,
            BenchmarkDatasetSplit split,
            BenchmarkTrackCategory category
    ) {
        return new BenchmarkExample(
                id,
                BenchmarkExpectedOutcome.NO_MATCH,
                sourceTrack,
                null,
                BenchmarkVerificationStatus.VERIFIED,
                evidence,
                null,
                split,
                category
        );
    }

    /**
     * Factory for creating an unverified match example.
     *
     * @param id unique benchmark ID
     * @param sourceTrack source track metadata
     * @param targetTrack target YouTube candidate
     * @param split dataset split
     * @param category category tag
     * @return constructed BenchmarkExample
     */
    public static BenchmarkExample unverifiedMatch(
            String id,
            BenchmarkSourceTrack sourceTrack,
            BenchmarkTargetCandidate targetTrack,
            BenchmarkDatasetSplit split,
            BenchmarkTrackCategory category
    ) {
        return new BenchmarkExample(
                id,
                BenchmarkExpectedOutcome.MATCH,
                sourceTrack,
                targetTrack,
                BenchmarkVerificationStatus.UNVERIFIED,
                null,
                null,
                split,
                category
        );
    }

    public boolean isPositiveMatch() {
        return expectedOutcome.isMatch();
    }

    public boolean isNoMatch() {
        return expectedOutcome.isNoMatch();
    }

    public boolean isVerified() {
        return verificationStatus.isVerified();
    }

    public Optional<BenchmarkTargetCandidate> getTargetTrack() {
        return Optional.ofNullable(targetTrack);
    }

    public Optional<String> getVerificationEvidence() {
        return Optional.ofNullable(verificationEvidence);
    }

    public Optional<String> getNotes() {
        return Optional.ofNullable(notes);
    }
}
