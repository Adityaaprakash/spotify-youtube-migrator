-- V4__phase2_oauth_connection_status.sql

ALTER TABLE oauth_connections
ADD COLUMN status VARCHAR(50) NOT NULL DEFAULT 'CONNECTED';
