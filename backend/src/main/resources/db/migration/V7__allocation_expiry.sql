ALTER TABLE allocations ADD COLUMN not_started_anomaly_at TIMESTAMP(9) WITH TIME ZONE;
ALTER TABLE allocations ADD COLUMN overdue_notified_expires_at TIMESTAMP(9) WITH TIME ZONE;

ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_action_check;
ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_action_check CHECK (
    action IN ('ALLOCATED', 'STARTED', 'RETURNED', 'UNSTARTED_EXPIRED', 'MARKED_ANOMALY', 'MARKED_OVERDUE')
);

ALTER TABLE inbox_messages DROP CONSTRAINT inbox_messages_allocation_uk;
ALTER TABLE inbox_messages DROP CONSTRAINT inbox_messages_type_check;
ALTER TABLE inbox_messages ADD CONSTRAINT inbox_messages_type_check CHECK (
    message_type IN ('ALLOCATED', 'UNSTARTED_EXPIRED', 'ANOMALY', 'OVERDUE')
);
ALTER TABLE inbox_messages ADD CONSTRAINT inbox_messages_allocation_type_uk UNIQUE (allocation_id, message_type);
