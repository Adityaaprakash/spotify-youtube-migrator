package com.spotifyyoutube.migrator.migration.execution.domain;

import com.spotifyyoutube.migrator.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * Persisted, append-only audit event capturing execution lifecycle transitions,
 * retries, worker leases, and compensating actions (Phase 14D).
 */
@Entity
@Table(name = "execution_audit_events")
public class ExecutionAuditEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    private MigrationExecution execution;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private ExecutionAuditEventType eventType;

    @Column(name = "previous_state", length = 50)
    private String previousState;

    @Column(name = "new_state", length = 50)
    private String newState;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "actor", length = 100)
    private String actor;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rollback_record_id")
    private ExecutionRollbackRecord rollbackRecord;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reconciliation_record_id")
    private ExecutionReconciliationRecord reconciliationRecord;

    public ExecutionAuditEvent() {
    }

    public ExecutionAuditEvent(
            MigrationExecution execution,
            ExecutionAuditEventType eventType,
            String previousState,
            String newState,
            String reason,
            String actor,
            String correlationId,
            String details
    ) {
        this.execution = Objects.requireNonNull(execution, "MigrationExecution must not be null");
        this.eventType = Objects.requireNonNull(eventType, "ExecutionAuditEventType must not be null");
        this.previousState = previousState;
        this.newState = newState;
        this.reason = reason;
        this.actor = actor != null ? actor : "SYSTEM";
        this.correlationId = correlationId;
        this.details = details;
    }

    public static ExecutionAuditEvent stateTransition(
            MigrationExecution execution,
            MigrationExecutionStatus previousState,
            MigrationExecutionStatus newState,
            String reason,
            String actor,
            String correlationId
    ) {
        return new ExecutionAuditEvent(
                execution,
                ExecutionAuditEventType.STATE_TRANSITION,
                previousState != null ? previousState.name() : null,
                newState != null ? newState.name() : null,
                reason,
                actor,
                correlationId,
                null
        );
    }

    public static ExecutionAuditEvent retryScheduled(
            MigrationExecution execution,
            int attemptCount,
            long backoffMs,
            String failureClassification,
            String reason,
            String actor,
            String correlationId
    ) {
        String details = String.format("attempt=%d, backoffMs=%d, failureClass=%s",
                attemptCount, backoffMs, failureClassification != null ? failureClassification : "UNKNOWN");
        return new ExecutionAuditEvent(
                execution,
                ExecutionAuditEventType.RETRY_SCHEDULED,
                null,
                null,
                reason,
                actor,
                correlationId,
                details
        );
    }

    public static ExecutionAuditEvent leaseEvent(
            MigrationExecution execution,
            ExecutionAuditEventType eventType,
            String workerId,
            long leaseGeneration,
            String reason
    ) {
        String details = String.format("worker=%s, generation=%d", workerId, leaseGeneration);
        return new ExecutionAuditEvent(
                execution,
                eventType,
                null,
                null,
                reason,
                workerId != null ? "WORKER:" + workerId : "SCHEDULER",
                String.valueOf(leaseGeneration),
                details
        );
    }

    public static ExecutionAuditEvent rollbackEvent(
            MigrationExecution execution,
            ExecutionAuditEventType eventType,
            ExecutionRollbackRecord rollbackRecord,
            String reason,
            String actor
    ) {
        ExecutionAuditEvent event = new ExecutionAuditEvent(
                execution,
                eventType,
                null,
                null,
                reason,
                actor,
                null,
                rollbackRecord != null ? "targetPosition=" + rollbackRecord.getTargetPosition() : null
        );
        event.setRollbackRecord(rollbackRecord);
        return event;
    }

    public static ExecutionAuditEvent reconciliationEvent(
            MigrationExecution execution,
            ExecutionReconciliationRecord reconciliationRecord,
            String reason,
            String actor
    ) {
        ExecutionAuditEvent event = new ExecutionAuditEvent(
                execution,
                ExecutionAuditEventType.RECONCILIATION_COMPLETED,
                null,
                null,
                reason,
                actor,
                null,
                reconciliationRecord != null ? "outcome=" + reconciliationRecord.getReconciliationOutcome() : null
        );
        event.setReconciliationRecord(reconciliationRecord);
        return event;
    }

    public MigrationExecution getExecution() {
        return execution;
    }

    public void setExecution(MigrationExecution execution) {
        this.execution = execution;
    }

    public ExecutionAuditEventType getEventType() {
        return eventType;
    }

    public void setEventType(ExecutionAuditEventType eventType) {
        this.eventType = eventType;
    }

    public String getPreviousState() {
        return previousState;
    }

    public void setPreviousState(String previousState) {
        this.previousState = previousState;
    }

    public String getNewState() {
        return newState;
    }

    public void setNewState(String newState) {
        this.newState = newState;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public ExecutionRollbackRecord getRollbackRecord() {
        return rollbackRecord;
    }

    public void setRollbackRecord(ExecutionRollbackRecord rollbackRecord) {
        this.rollbackRecord = rollbackRecord;
    }

    public ExecutionReconciliationRecord getReconciliationRecord() {
        return reconciliationRecord;
    }

    public void setReconciliationRecord(ExecutionReconciliationRecord reconciliationRecord) {
        this.reconciliationRecord = reconciliationRecord;
    }
}
