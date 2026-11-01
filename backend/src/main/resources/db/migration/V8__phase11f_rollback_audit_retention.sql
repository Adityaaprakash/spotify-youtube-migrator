-- V8__phase11f_rollback_audit_retention.sql

-- Replace ON DELETE CASCADE with ON DELETE RESTRICT on rollback audit records
-- to enforce audit retention and prevent silent deletion of compensating mutation history.

ALTER TABLE execution_rollback_records
    DROP CONSTRAINT fk_rollback_execution,
    ADD CONSTRAINT fk_rollback_execution
        FOREIGN KEY (execution_id)
        REFERENCES migration_executions (id)
        ON DELETE RESTRICT;

ALTER TABLE execution_rollback_records
    DROP CONSTRAINT fk_rollback_track_record,
    ADD CONSTRAINT fk_rollback_track_record
        FOREIGN KEY (track_record_id)
        REFERENCES execution_track_records (id)
        ON DELETE RESTRICT;
