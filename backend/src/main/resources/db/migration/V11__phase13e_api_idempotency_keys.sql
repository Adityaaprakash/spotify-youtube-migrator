-- V11__phase13e_api_idempotency_keys.sql
-- API Idempotency Contract and Terminal-Only Lifecycle Retention (Phase 13E)

CREATE TABLE migration_idempotency_keys (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    execution_id UUID NOT NULL,
    response_status INT NOT NULL,
    response_payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_idempotency_execution FOREIGN KEY (execution_id) REFERENCES migration_executions (id) ON DELETE RESTRICT,
    CONSTRAINT uq_idempotency_user_key UNIQUE (user_id, idempotency_key)
);

-- Partial index for expired key cleanup sweeper
CREATE INDEX idx_idempotency_expires_at
    ON migration_idempotency_keys (expires_at)
    WHERE expires_at IS NOT NULL;

-- Index for updating idempotency records when execution reaches terminal state
CREATE INDEX idx_idempotency_execution_id
    ON migration_idempotency_keys (execution_id);
