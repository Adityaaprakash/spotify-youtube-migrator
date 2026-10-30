package com.spotifyyoutube.migrator.migration.application.execution.contract;

/**
 * Structured error codes identifying specific reasons why an approved migration is ineligible for execution.
 */
public enum ValidationFailureCode {

    /** Required execution inputs, session identifier, or user identity is missing. */
    MISSING_CONTEXT,

    /** Authenticated user is not authorized to execute this review session (ownership mismatch). */
    UNAUTHORIZED,

    /** Review session is not in COMPLETED status or still has tracks awaiting human decision. */
    INCOMPLETE_REVIEW,

    /** A track is in an unresolved state (e.g. AWAITING_REVIEW, no match, or unreviewed failure). */
    UNRESOLVED_TRACK,

    /** A track has an unresolved duplicate destination candidate conflict. */
    UNRESOLVED_CONFLICT,

    /** A selected candidate fails validation (blank ID/title, negative duration, or malformed data). */
    INVALID_CANDIDATE,

    /** A candidate is associated with a different source track than requested. */
    CANDIDATE_ASSOCIATION_MISMATCH,

    /** The referenced review session does not match the requested source playlist or plan context. */
    INCONSISTENT_CONTEXT,

    /** An approved track is missing a valid, non-blank destination track identity. */
    INVALID_DESTINATION_ID,

    /** The review session or track is in an invalid lifecycle state for execution (e.g. CANCELLED). */
    INVALID_STATE,

    /** Multiple approved tracks share the identical destination track ID. */
    DUPLICATE_DESTINATION_TRACK,

    /** Source tracks violate contiguous 0-indexed position ordering. */
    ORDERING_MISMATCH,

    /** No source tracks are approved for destination migration. */
    EMPTY_SELECTION
}
