-- V1__baseline.sql
-- Initial Baseline Phase 0
CREATE TABLE IF NOT EXISTS flyway_baseline (
    id SERIAL PRIMARY KEY,
    initialized_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
