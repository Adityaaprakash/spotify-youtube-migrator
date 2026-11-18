-- V17__phase16e_database_security_and_indexes.sql
-- Database security hardening: query-justified index optimizations (Phase 16E)

-- 1. Index for expired OAuth state purge sweep (OAuthStateRepository.deleteExpiredStates)
CREATE INDEX idx_oauth_states_expires_at
    ON oauth_states (expires_at);

-- 2. Composite index for user-scoped review session discovery in reverse chronological order
-- (ReviewSessionRepository.findByUserIdOrderByCreatedAtDesc)
CREATE INDEX idx_reviewsessions_user_created
    ON review_sessions (user_id, created_at DESC);

-- 3. Composite index for ordered execution rollback record retrieval
-- (ExecutionRollbackRecordRepository.findByExecutionIdOrderByTargetPositionAsc)
CREATE INDEX idx_rollback_execution_target_position
    ON execution_rollback_records (execution_id, target_position ASC);

-- 4. Composite index for ordered execution reconciliation record retrieval
-- (ExecutionReconciliationRecordRepository.findByExecutionIdOrderByCreatedAtAsc)
CREATE INDEX idx_reconciliation_execution_created
    ON execution_reconciliation_records (execution_id, created_at ASC);
