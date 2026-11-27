CREATE TABLE renewal_requests (
    id UUID PRIMARY KEY,
    allocation_id UUID NOT NULL REFERENCES allocations(id),
    gpu_id INTEGER NOT NULL REFERENCES gpus(id),
    member_id UUID NOT NULL REFERENCES accounts(id),
    requested_expires_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    approved_expires_at TIMESTAMP(9) WITH TIME ZONE,
    reviewed_by UUID REFERENCES accounts(id),
    reviewed_at TIMESTAMP(9) WITH TIME ZONE,
    review_reason VARCHAR(500),
    created_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    CONSTRAINT renewal_requests_allocation_gpu_fk
        FOREIGN KEY (allocation_id, gpu_id) REFERENCES allocations(id, gpu_id)
);

CREATE INDEX renewal_requests_member_idx ON renewal_requests(member_id, created_at DESC);
CREATE INDEX renewal_requests_status_idx ON renewal_requests(status, created_at ASC);

ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_allocation_action_uk;
ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_action_check;
ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_action_check CHECK (
    action IN (
        'ALLOCATED', 'STARTED', 'RETURNED', 'UNSTARTED_EXPIRED',
        'MARKED_ANOMALY', 'MARKED_OVERDUE', 'VERIFIED_RELEASED',
        'EMERGENCY_APPROVED', 'EMERGENCY_REJECTED', 'QUEUE_REORDERED',
        'RENEWAL_APPROVED', 'RENEWAL_REJECTED'
    )
);

ALTER TABLE inbox_messages DROP CONSTRAINT inbox_messages_allocation_type_uk;
ALTER TABLE inbox_messages DROP CONSTRAINT inbox_messages_type_check;
ALTER TABLE inbox_messages ADD CONSTRAINT inbox_messages_type_check CHECK (
    message_type IN (
        'ALLOCATED', 'UNSTARTED_EXPIRED', 'ANOMALY', 'OVERDUE', 'VERIFIED_RELEASED',
        'EMERGENCY_APPROVED', 'EMERGENCY_REJECTED', 'QUEUE_REORDERED',
        'RENEWAL_APPROVED', 'RENEWAL_REJECTED'
    )
);
