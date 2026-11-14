-- Phase 13F: External Operation Idempotency & Reconciliation

-- 1. Relax NOT NULL on destination_playlist_id to permit durable intent in ALLOCATING_DESTINATION state
ALTER TABLE migration_executions ALTER COLUMN destination_playlist_id DROP NOT NULL;

-- 2. Add playlist creation operation fields to migration_executions
ALTER TABLE migration_executions ADD COLUMN playlist_creation_operation_id UUID;
ALTER TABLE migration_executions ADD COLUMN playlist_creation_attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE migration_executions ADD COLUMN playlist_creation_max_attempts INT NOT NULL DEFAULT 3;
ALTER TABLE migration_executions ADD COLUMN playlist_creation_next_attempt_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE migration_executions ADD COLUMN playlist_creation_failure_code VARCHAR(50);

-- Partial index for fast operation ID lookups
CREATE INDEX idx_migrationexecutions_playlist_op ON migration_executions(playlist_creation_operation_id)
    WHERE playlist_creation_operation_id IS NOT NULL;

-- 3. Add playlist_creation_operation_id to migration_idempotency_keys for State B recovery correlation
ALTER TABLE migration_idempotency_keys ADD COLUMN playlist_creation_operation_id UUID;
