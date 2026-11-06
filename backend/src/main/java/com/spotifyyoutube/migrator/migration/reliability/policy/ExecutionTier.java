package com.spotifyyoutube.migrator.migration.reliability.policy;

/**
 * Execution strategy tier for a retry attempt (Phase 13C).
 * <p>
 * Implements Phase 13's strict 2.0-second execution boundary (ADR-031):
 * <ul>
 *   <li>{@link #IN_FLIGHT}: Short delay (&le; 2,000 ms) permitted for a single bounded micro-retry on the active thread.</li>
 *   <li>{@link #DEFERRED}: Delay &gt; 2,000 ms, subsequent attempts, quota deferral, or reconciliation; worker thread released immediately.</li>
 *   <li>{@link #NONE}: Retry not permitted.</li>
 * </ul>
 */
public enum ExecutionTier {

    /**
     * Bounded short micro-retry permitted on worker thread (delay &le; 2,000 ms, first retry attempt only).
     */
    IN_FLIGHT,

    /**
     * Long delay (&gt; 2,000 ms), subsequent retry attempts, quota deferral, or reconciliation required.
     * Worker thread must be released immediately; execution to be scheduled durably (Phase 13D/13F).
     */
    DEFERRED,

    /**
     * No retry execution permitted (e.g. non-retryable error, budget exhausted, reauthorization needed).
     */
    NONE
}
