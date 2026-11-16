-- Phase 13G: Migration Recovery & Resume Integration
-- Adds durable rollback intent tracking to migration_executions

ALTER TABLE migration_executions ADD COLUMN rollback_requested_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE migration_executions ADD COLUMN rollback_target_positions TEXT;
ALTER TABLE migration_executions ADD COLUMN rollback_completed_at TIMESTAMP WITH TIME ZONE;

-- Partial index for active rollback recovery
CREATE INDEX idx_migrationexecutions_rollback_active
    ON migration_executions(status, rollback_requested_at, lease_until)
    WHERE rollback_requested_at IS NOT NULL;
