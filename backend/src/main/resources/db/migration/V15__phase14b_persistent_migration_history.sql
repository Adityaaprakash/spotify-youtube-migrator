-- V15__phase14b_persistent_migration_history.sql
-- Persistent Migration Records & History Enhancements (Phase 14B)

-- 1. Extend migration_executions with total source tracks, matched tracks count, and failure summary
ALTER TABLE migration_executions
    ADD COLUMN total_source_tracks INT NOT NULL DEFAULT 0,
    ADD COLUMN matched_tracks INT NOT NULL DEFAULT 0,
    ADD COLUMN failure_summary TEXT;

-- Backfill total_source_tracks and matched_tracks for existing rows using total_approved_tracks
UPDATE migration_executions
SET total_source_tracks = total_approved_tracks,
    matched_tracks = total_approved_tracks
WHERE total_source_tracks = 0 AND total_approved_tracks > 0;

-- Composite index for fast user migration history listing in reverse chronological order
CREATE INDEX idx_migrationexecutions_user_created
    ON migration_executions (user_id, created_at DESC);

-- 2. Extend execution_track_records with destination metadata, decision provenance, and match scoring
ALTER TABLE execution_track_records
    ADD COLUMN destination_title VARCHAR(500),
    ADD COLUMN destination_channel VARCHAR(500),
    ADD COLUMN destination_duration_ms BIGINT,
    ADD COLUMN decision_source VARCHAR(50),
    ADD COLUMN match_score DOUBLE PRECISION,
    ADD COLUMN match_decision VARCHAR(50);
