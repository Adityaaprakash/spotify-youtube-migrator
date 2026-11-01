-- V7__phase11f_failure_recovery_and_rollback_schema.sql

ALTER TABLE migration_executions
ADD COLUMN rolled_back_insertions INT NOT NULL DEFAULT 0;

CREATE TABLE execution_rollback_records (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    execution_id UUID NOT NULL,
    track_record_id UUID NOT NULL,
    target_position INT NOT NULL,
    destination_track_id VARCHAR(255) NOT NULL,
    destination_playlist_item_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    error_message TEXT,
    attempted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_rollback_execution FOREIGN KEY (execution_id) REFERENCES migration_executions (id) ON DELETE CASCADE,
    CONSTRAINT fk_rollback_track_record FOREIGN KEY (track_record_id) REFERENCES execution_track_records (id) ON DELETE CASCADE
);

CREATE INDEX idx_rollback_execution_id ON execution_rollback_records(execution_id);
CREATE INDEX idx_rollback_track_record_id ON execution_rollback_records(track_record_id);
