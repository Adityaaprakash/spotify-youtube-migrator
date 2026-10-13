package com.spotifyyoutube.migrator.matching.benchmark;

/**
 * Audit and verification status of a ground-truth benchmark example.
 * <p>
 * Ensures that human-verified ground-truth is strictly separated from
 * unverified or heuristically generated candidates.
 */
public enum BenchmarkVerificationStatus {

    /**
     * Confirmed by manual human inspection with auditable provenance and evidence.
     */
    VERIFIED,

    /**
     * Candidate ground-truth identified algorithmically or from test fixtures,
     * awaiting independent human verification before inclusion in scored evaluation.
     */
    UNVERIFIED,

    /**
     * Flagged during review due to ambiguity, conflicting audio releases,
     * or disputed target video IDs.
     */
    DISPUTED;

    /**
     * Indicates whether this example has been fully verified by a human expert.
     *
     * @return true if verified, false otherwise
     */
    public boolean isVerified() {
        return this == VERIFIED;
    }
}
