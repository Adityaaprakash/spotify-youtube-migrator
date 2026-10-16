-- V5__phase10_review_session_schema.sql

CREATE TABLE review_sessions (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    user_id UUID NOT NULL,
    source_playlist_id VARCHAR(255) NOT NULL,
    source_playlist_title VARCHAR(500) NOT NULL,
    plan_id UUID,
    status VARCHAR(50) NOT NULL DEFAULT 'IN_PROGRESS',
    total_tracks INT NOT NULL DEFAULT 0,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_reviewsession_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_reviewsessions_user_id ON review_sessions(user_id);
CREATE INDEX idx_reviewsessions_status ON review_sessions(status);
CREATE INDEX idx_reviewsessions_plan_id ON review_sessions(plan_id);

CREATE TABLE review_tracks (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    session_id UUID NOT NULL,
    position INT NOT NULL,
    source_track_id VARCHAR(255) NOT NULL,
    source_title VARCHAR(500) NOT NULL,
    source_artist VARCHAR(500) NOT NULL,

    -- Original automatic matching evidence (immutable provenance)
    original_classification VARCHAR(50) NOT NULL,
    original_candidate_id VARCHAR(255),
    original_candidate_title VARCHAR(500),
    original_candidate_channel VARCHAR(500),
    original_candidate_duration_ms BIGINT,
    original_match_score DOUBLE PRECISION,
    original_calibrated_probability DOUBLE PRECISION,
    original_match_decision VARCHAR(50),
    original_match_reasons TEXT,

    -- Current review decision state
    review_status VARCHAR(50) NOT NULL DEFAULT 'AWAITING_REVIEW',
    decision_source VARCHAR(50) NOT NULL DEFAULT 'AUTOMATIC',
    selected_candidate_id VARCHAR(255),
    selected_candidate_title VARCHAR(500),
    selected_candidate_channel VARCHAR(500),
    selected_candidate_duration_ms BIGINT,
    is_excluded BOOLEAN NOT NULL DEFAULT FALSE,
    has_conflict BOOLEAN NOT NULL DEFAULT FALSE,
    conflict_reason VARCHAR(1000),
    decision_notes TEXT,

    CONSTRAINT fk_reviewtrack_session FOREIGN KEY (session_id) REFERENCES review_sessions (id) ON DELETE CASCADE,
    CONSTRAINT uq_reviewtrack_session_position UNIQUE (session_id, position)
);

CREATE INDEX idx_reviewtracks_session_id ON review_tracks(session_id);
CREATE INDEX idx_reviewtracks_session_status ON review_tracks(session_id, review_status);
CREATE INDEX idx_reviewtracks_session_source_track ON review_tracks(session_id, source_track_id);
