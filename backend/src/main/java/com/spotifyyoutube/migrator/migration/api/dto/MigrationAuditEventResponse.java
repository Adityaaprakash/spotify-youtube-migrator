package com.spotifyyoutube.migrator.migration.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Presentation response DTO representing an execution lifecycle audit event in a migration timeline (Phase 14D).
 *
 * @param id audit record identifier
 * @param executionId associated migration execution identifier
 * @param eventType type of event (e.g. STATE_TRANSITION, RETRY_SCHEDULED, LEASE_ACQUIRED)
 * @param previousState preceding execution lifecycle status
 * @param newState target execution lifecycle status
 * @param reason human-readable justification or context description
 * @param actor user, worker node, or system component triggering the event
 * @param correlationId correlation identifier (operation or lease token)
 * @param details safe structured event metadata
 * @param rollbackRecordId reference to associated rollback audit record if applicable
 * @param reconciliationRecordId reference to associated reconciliation audit record if applicable
 * @param createdAt timestamp when the audit event was recorded
 */
public record MigrationAuditEventResponse(
        UUID id,
        UUID executionId,
        String eventType,
        String previousState,
        String newState,
        String reason,
        String actor,
        String correlationId,
        String details,
        UUID rollbackRecordId,
        UUID reconciliationRecordId,
        Instant createdAt
) {
}
