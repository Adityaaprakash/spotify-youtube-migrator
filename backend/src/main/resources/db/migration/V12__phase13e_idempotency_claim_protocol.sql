-- V12__phase13e_idempotency_claim_protocol.sql
-- Concurrency Hardening: Database-Backed Atomic Idempotency Claim Protocol (Phase 13E)

-- Allow nullable execution_id, response_status, response_payload for PENDING claims
ALTER TABLE migration_idempotency_keys ALTER COLUMN execution_id DROP NOT NULL;
ALTER TABLE migration_idempotency_keys ALTER COLUMN response_status DROP NOT NULL;
ALTER TABLE migration_idempotency_keys ALTER COLUMN response_payload DROP NOT NULL;

-- Add lifecycle status, claim expiration timestamp, location header, review session link, and fencing tokens
ALTER TABLE migration_idempotency_keys
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    ADD COLUMN claim_expires_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN location_header VARCHAR(2048),
    ADD COLUMN review_session_id UUID,
    ADD COLUMN claim_owner VARCHAR(128),
    ADD COLUMN claim_generation BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN heartbeat_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN destination_playlist_id VARCHAR(255),
    ADD COLUMN claim_stage VARCHAR(32) NOT NULL DEFAULT 'ACQUIRED';

-- Partial index for active and stale PENDING claims
CREATE INDEX idx_idempotency_pending_claims
    ON migration_idempotency_keys (status, claim_expires_at)
    WHERE status = 'PENDING';

-- Index for claim ownership and fencing generation verification
CREATE INDEX idx_idempotency_claim_fencing
    ON migration_idempotency_keys (claim_owner, claim_generation);

-- Partial index for finding pending claims by review session ID
CREATE INDEX idx_idempotency_pending_session
    ON migration_idempotency_keys (review_session_id)
    WHERE status = 'PENDING' AND review_session_id IS NOT NULL;
