-- V3__phase2_auth_oauth_schema.sql

ALTER TABLE users 
ADD COLUMN password_hash VARCHAR(255),
ADD COLUMN display_name VARCHAR(255),
ADD COLUMN status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
DROP COLUMN spotify_id,
DROP COLUMN youtube_id;

CREATE TABLE oauth_connections (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    user_id UUID NOT NULL,
    provider VARCHAR(50) NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    access_token TEXT NOT NULL,
    refresh_token TEXT,
    expires_at TIMESTAMP WITH TIME ZONE,
    scopes TEXT,
    CONSTRAINT fk_oauth_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_oauth_user_provider UNIQUE (user_id, provider),
    CONSTRAINT uq_oauth_provider_identity UNIQUE (provider, provider_user_id)
);

CREATE INDEX idx_oauth_connections_user_id ON oauth_connections(user_id);

CREATE TABLE oauth_states (
    state VARCHAR(255) PRIMARY KEY,
    user_id UUID NOT NULL,
    provider VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_oauthstate_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_oauth_states_user_id ON oauth_states(user_id);
