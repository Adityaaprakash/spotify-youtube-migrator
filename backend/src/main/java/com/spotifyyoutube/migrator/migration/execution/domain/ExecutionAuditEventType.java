package com.spotifyyoutube.migrator.migration.execution.domain;

/**
 * Event vocabulary for migration execution audit logs and lifecycle tracking (Phase 14D).
 */
public enum ExecutionAuditEventType {

    /**
     * Migration execution transitioned from one lifecycle status to another.
     */
    STATE_TRANSITION,

    /**
     * A transient or retryable provider failure was detected and a future retry was scheduled.
     */
    RETRY_SCHEDULED,

    /**
     * A scheduled retry execution attempt began.
     */
    RETRY_EXECUTION,

    /**
     * The maximum permitted retry attempts were exhausted for a track or execution.
     */
    RETRY_EXHAUSTED,

    /**
     * A worker node acquired an active execution claim and lease.
     */
    LEASE_ACQUIRED,

    /**
     * An execution lease was released back to the queue or scheduler.
     */
    LEASE_RELEASED,

    /**
     * A worker lease expired or was lost due to fencing or timeout.
     */
    LEASE_EXPIRED,

    /**
     * A compensating rollback operation was requested for one or more tracks.
     */
    ROLLBACK_REQUESTED,

    /**
     * A compensating rollback operation settled (fully or partially completed).
     */
    ROLLBACK_COMPLETED,

    /**
     * A remote destination state inspection and reconciliation operation completed.
     */
    RECONCILIATION_COMPLETED
}
