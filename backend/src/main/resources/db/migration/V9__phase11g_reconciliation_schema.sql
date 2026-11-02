-- V9__phase11g_reconciliation_schema.sql
-- Dedicated audit schema for destination state reconciliation and remote verification (Phase 11G)

CREATE TABLE execution_reconciliation_records (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    execution_id UUID NOT NULL,
    track_record_id UUID NOT NULL,
    reconciliation_type VARCHAR(50) NOT NULL,
    previous_track_status VARCHAR(50) NOT NULL,
    resolved_track_status VARCHAR(50) NOT NULL,
    reconciliation_outcome VARCHAR(50) NOT NULL,
    destination_playlist_id VARCHAR(255) NOT NULL,
    destination_playlist_item_id VARCHAR(255),
    destination_track_id VARCHAR(255),
    evidence_summary TEXT,
    evidence_timestamp TIMESTAMP WITH TIME ZONE,
    inspection_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    inspection_completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    failure_code VARCHAR(100),
    sanitized_error_message TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_reconciliation_execution FOREIGN KEY (execution_id) REFERENCES migration_executions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_reconciliation_track_record FOREIGN KEY (track_record_id) REFERENCES execution_track_records (id) ON DELETE RESTRICT
);

CREATE INDEX idx_reconciliation_execution_id ON execution_reconciliation_records(execution_id);
CREATE INDEX idx_reconciliation_track_record_id ON execution_reconciliation_records(track_record_id);
CREATE INDEX idx_reconciliation_outcome ON execution_reconciliation_records(reconciliation_outcome);
