ALTER TABLE requests ADD COLUMN request_type VARCHAR(16) NOT NULL DEFAULT 'ORDINARY';
ALTER TABLE requests ADD COLUMN emergency_status VARCHAR(16);
ALTER TABLE requests ADD COLUMN emergency_reviewed_at TIMESTAMP(9) WITH TIME ZONE;
ALTER TABLE requests ADD COLUMN emergency_reviewed_by UUID;
ALTER TABLE requests ADD COLUMN emergency_review_reason VARCHAR(500);

ALTER TABLE requests ADD CONSTRAINT requests_type_check CHECK (request_type IN ('ORDINARY', 'EMERGENCY'));
ALTER TABLE requests ADD CONSTRAINT requests_emergency_status_check CHECK (
    emergency_status IS NULL OR emergency_status IN ('PENDING', 'APPROVED', 'REJECTED')
);
ALTER TABLE requests ADD CONSTRAINT requests_emergency_consistency_check CHECK (
    (request_type = 'ORDINARY' AND emergency_status IS NULL)
    OR (request_type = 'EMERGENCY' AND emergency_status IS NOT NULL)
);
ALTER TABLE requests ADD CONSTRAINT requests_emergency_reviewer_fk
    FOREIGN KEY (emergency_reviewed_by) REFERENCES accounts(id);

ALTER TABLE audit_logs ALTER COLUMN queue_item_id SET NULL;
ALTER TABLE audit_logs ALTER COLUMN allocation_id SET NULL;
ALTER TABLE audit_logs ALTER COLUMN gpu_id SET NULL;
ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_action_check;
ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_action_check CHECK (
    action IN (
        'ALLOCATED', 'STARTED', 'RETURNED', 'UNSTARTED_EXPIRED',
        'MARKED_ANOMALY', 'MARKED_OVERDUE', 'VERIFIED_RELEASED',
        'EMERGENCY_APPROVED', 'EMERGENCY_REJECTED'
    )
);

ALTER TABLE inbox_messages ALTER COLUMN allocation_id SET NULL;
ALTER TABLE inbox_messages ALTER COLUMN gpu_id SET NULL;
ALTER TABLE inbox_messages DROP CONSTRAINT inbox_messages_type_check;
ALTER TABLE inbox_messages ADD CONSTRAINT inbox_messages_type_check CHECK (
    message_type IN (
        'ALLOCATED', 'UNSTARTED_EXPIRED', 'ANOMALY', 'OVERDUE', 'VERIFIED_RELEASED',
        'EMERGENCY_APPROVED', 'EMERGENCY_REJECTED'
    )
);
