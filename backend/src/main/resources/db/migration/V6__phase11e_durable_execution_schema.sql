-- V6__phase11e_durable_execution_schema.sql

CREATE TABLE migration_executions (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    user_id UUID NOT NULL,
    review_session_id UUID NOT NULL UNIQUE,
    plan_id UUID,
    source_playlist_id VARCHAR(255) NOT NULL,
    source_playlist_title VARCHAR(500) NOT NULL,
    destination_platform VARCHAR(50) NOT NULL,
    destination_playlist_id VARCHAR(255) NOT NULL,
    destination_playlist_url VARCHAR(2048),
    status VARCHAR(50) NOT NULL DEFAULT 'INITIALIZED',
    total_approved_tracks INT NOT NULL DEFAULT 0,
    successful_insertions INT NOT NULL DEFAULT 0,
    failed_insertions INT NOT NULL DEFAULT 0,
    uncertain_insertions INT NOT NULL DEFAULT 0,
    skipped_insertions INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_migrationexecution_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_migrationexecution_reviewsession FOREIGN KEY (review_session_id) REFERENCES review_sessions (id) ON DELETE CASCADE
);

CREATE INDEX idx_migrationexecutions_user_id ON migration_executions(user_id);
CREATE INDEX idx_migrationexecutions_review_session_id ON migration_executions(review_session_id);
CREATE INDEX idx_migrationexecutions_status ON migration_executions(status);

CREATE TABLE execution_track_records (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    execution_id UUID NOT NULL,
    target_position INT NOT NULL,
    source_position INT NOT NULL,
    source_track_id VARCHAR(255) NOT NULL,
    source_title VARCHAR(500) NOT NULL,
    source_artist VARCHAR(500) NOT NULL,
    destination_track_id VARCHAR(255) NOT NULL,
    destination_playlist_item_id VARCHAR(255),
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    error_message TEXT,
    attempt_count INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_executiontrack_execution FOREIGN KEY (execution_id) REFERENCES migration_executions (id) ON DELETE CASCADE,
    CONSTRAINT uq_executiontrack_execution_position UNIQUE (execution_id, target_position)
);

CREATE INDEX idx_executiontracks_execution_id ON execution_track_records(execution_id);
CREATE INDEX idx_executiontracks_execution_status ON execution_track_records(execution_id, status);
CREATE INDEX idx_executiontracks_execution_target_position ON execution_track_records(execution_id, target_position);
