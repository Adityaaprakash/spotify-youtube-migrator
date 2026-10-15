package com.spotifyyoutube.migrator.migration.application.plan;

/**
 * Mutually exclusive classification for a source track within a migration plan.
 * Defines the intended disposition and eligibility of the track prior to destination mutation.
 */
public enum PlanTrackClassification {

    /**
     * High-confidence match meeting automated migration criteria.
     * Eligible for automated insertion into the destination playlist without manual review.
     */
    AUTO_MATCH,

    /**
     * Borderline or ambiguous match requiring human review and approval.
     * Held back from automated insertion to protect destination playlist quality.
     */
    REVIEW_REQUIRED,

    /**
     * No acceptable destination candidate was found meeting minimum similarity thresholds.
     */
    UNMATCHED,

    /**
     * Candidate matched a destination track that is already assigned to a preceding track
     * within the same migration plan, prevented by playlist deduplication policy.
     */
    DUPLICATE,

    /**
     * Technical or diagnostic failure occurred during track normalization, discovery, or evaluation.
     */
    FAILED,

    /**
     * Explicitly excluded from migration by rule or user preference.
     */
    EXCLUDED
}
