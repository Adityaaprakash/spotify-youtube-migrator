-- V16__phase14d_execution_audit_events.sql
-- Flyway migration for Phase 14D Execution Audit Events and State Transition History

CREATE TABLE execution_audit_events (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    previous_state VARCHAR(50),
    new_state VARCHAR(50),
    reason TEXT,
    actor VARCHAR(100),
    correlation_id VARCHAR(100),
    details TEXT,
    rollback_record_id UUID,
    reconciliation_record_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_audit_events_execution FOREIGN KEY (execution_id)
        REFERENCES migration_executions(id) ON DELETE CASCADE,
    CONSTRAINT fk_audit_events_rollback FOREIGN KEY (rollback_record_id)
        REFERENCES execution_rollback_records(id) ON DELETE SET NULL,
    CONSTRAINT fk_audit_events_reconciliation FOREIGN KEY (reconciliation_record_id)
        REFERENCES execution_reconciliation_records(id) ON DELETE SET NULL
);

-- Fast chronological timeline index per execution
CREATE INDEX idx_audit_events_execution_created
    ON execution_audit_events (execution_id, created_at ASC);

-- Index for filtering audit events by event type
CREATE INDEX idx_audit_events_type
    ON execution_audit_events (event_type);

-- Composite index for status-filtered user migration history queries (Phase 14C)
CREATE INDEX idx_migrationexecutions_user_status_created
    ON migration_executions (user_id, status, created_at DESC);
