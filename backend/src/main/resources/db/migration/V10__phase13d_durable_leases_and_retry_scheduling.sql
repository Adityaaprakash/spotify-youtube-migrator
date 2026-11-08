-- V10__phase13d_durable_leases_and_retry_scheduling.sql
-- Durable Worker Ownership, Generational Fencing, and Track-Level Retry Scheduling (Phase 13D-A)

-- 1. Durable Worker Lease and Aggregate-Level Scheduling Metadata on migration_executions
ALTER TABLE migration_executions
    ADD COLUMN lease_owner VARCHAR(128),
    ADD COLUMN lease_until TIMESTAMP WITH TIME ZONE,
    ADD COLUMN heartbeat_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN lease_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE;

-- Partial index for atomic execution claiming and expired lease reclamation
CREATE INDEX idx_migrationexecutions_lease_claim
    ON migration_executions (status, next_attempt_at, lease_until)
    WHERE status IN ('QUEUED', 'RUNNING');

-- 2. Track-Level Retry Scheduling Metadata on execution_track_records
ALTER TABLE execution_track_records
    ADD COLUMN max_attempts INT NOT NULL DEFAULT 3,
    ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_failure_classification VARCHAR(50),
    ADD COLUMN retry_backoff_ms BIGINT;

-- Partial index for finding eligible/due pending tracks within an execution
CREATE INDEX idx_executiontracks_retry_due
    ON execution_track_records (execution_id, status, next_attempt_at)
    WHERE status = 'PENDING';
