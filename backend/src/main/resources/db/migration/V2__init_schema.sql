-- V2__init_schema.sql

CREATE TABLE users (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    spotify_id VARCHAR(255) UNIQUE,
    youtube_id VARCHAR(255) UNIQUE
);

CREATE TABLE playlists (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    platform VARCHAR(50) NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    url VARCHAR(2048),
    image_url VARCHAR(2048),
    total_tracks INT,
    user_id UUID NOT NULL,
    CONSTRAINT fk_playlist_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_playlists_user_id ON playlists(user_id);

CREATE TABLE tracks (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    name VARCHAR(255) NOT NULL,
    artist VARCHAR(255) NOT NULL,
    album VARCHAR(255),
    duration_ms INT,
    external_id VARCHAR(255) NOT NULL,
    playlist_id UUID NOT NULL,
    CONSTRAINT fk_track_playlist FOREIGN KEY (playlist_id) REFERENCES playlists (id) ON DELETE CASCADE
);

CREATE INDEX idx_tracks_playlist_id ON tracks(playlist_id);

CREATE TABLE migration_jobs (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    user_id UUID NOT NULL,
    source_playlist_id VARCHAR(255) NOT NULL,
    source_platform VARCHAR(50) NOT NULL,
    target_platform VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    total_tracks INT,
    processed_tracks INT NOT NULL DEFAULT 0,
    failed_tracks INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    target_playlist_url VARCHAR(2048),
    CONSTRAINT fk_migrationjob_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_migrationjobs_user_id ON migration_jobs(user_id);
CREATE INDEX idx_migrationjobs_status ON migration_jobs(status);

CREATE TABLE migration_tasks (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    job_id UUID NOT NULL,
    source_track_id VARCHAR(255) NOT NULL,
    target_track_id VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    error_message TEXT,
    CONSTRAINT fk_migrationtask_job FOREIGN KEY (job_id) REFERENCES migration_jobs (id) ON DELETE CASCADE
);

CREATE INDEX idx_migrationtasks_job_id ON migration_tasks(job_id);
CREATE INDEX idx_migrationtasks_job_id_status ON migration_tasks(job_id, status);
