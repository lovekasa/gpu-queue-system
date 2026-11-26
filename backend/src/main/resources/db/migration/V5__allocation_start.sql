ALTER TABLE allocations ADD COLUMN started_at TIMESTAMP(9) WITH TIME ZONE;

ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_action_check;

ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_action_check CHECK (action IN ('ALLOCATED', 'STARTED'));
