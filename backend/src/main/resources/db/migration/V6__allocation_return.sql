ALTER TABLE allocations ADD COLUMN ended_at TIMESTAMP(9) WITH TIME ZONE;
ALTER TABLE allocations ADD COLUMN end_reason VARCHAR(32);

ALTER TABLE allocations ADD CONSTRAINT allocations_end_consistency_check CHECK (
    (ended_at IS NULL AND end_reason IS NULL)
    OR (ended_at IS NOT NULL AND end_reason IN ('RETURNED', 'REVOKED', 'UNSTARTED_EXPIRED', 'VERIFIED_RELEASED'))
);

ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_action_check;

ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_action_check CHECK (action IN ('ALLOCATED', 'STARTED', 'RETURNED'));
