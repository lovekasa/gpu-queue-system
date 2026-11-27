CREATE TABLE queue_reorders (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    gpu_id INTEGER NOT NULL REFERENCES gpus(id),
    target_queue_item_id UUID NOT NULL,
    before_queue_item_id UUID,
    after_queue_item_id UUID,
    actor_account_id UUID NOT NULL REFERENCES accounts(id),
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    CONSTRAINT queue_reorders_target_gpu_fk
        FOREIGN KEY (target_queue_item_id, gpu_id) REFERENCES queue_items(id, gpu_id),
    CONSTRAINT queue_reorders_before_gpu_fk
        FOREIGN KEY (before_queue_item_id, gpu_id) REFERENCES queue_items(id, gpu_id),
    CONSTRAINT queue_reorders_after_gpu_fk
        FOREIGN KEY (after_queue_item_id, gpu_id) REFERENCES queue_items(id, gpu_id),
    CONSTRAINT queue_reorders_not_self_before CHECK (before_queue_item_id IS NULL OR before_queue_item_id <> target_queue_item_id),
    CONSTRAINT queue_reorders_not_self_after CHECK (after_queue_item_id IS NULL OR after_queue_item_id <> target_queue_item_id),
    CONSTRAINT queue_reorders_anchor_present CHECK (before_queue_item_id IS NOT NULL OR after_queue_item_id IS NOT NULL)
);

CREATE INDEX queue_reorders_gpu_id_idx ON queue_reorders(gpu_id, id ASC);

ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_action_check;
ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_action_check CHECK (
    action IN (
        'ALLOCATED', 'STARTED', 'RETURNED', 'UNSTARTED_EXPIRED',
        'MARKED_ANOMALY', 'MARKED_OVERDUE', 'VERIFIED_RELEASED',
        'EMERGENCY_APPROVED', 'EMERGENCY_REJECTED', 'QUEUE_REORDERED'
    )
);

ALTER TABLE inbox_messages DROP CONSTRAINT inbox_messages_type_check;
ALTER TABLE inbox_messages ADD CONSTRAINT inbox_messages_type_check CHECK (
    message_type IN (
        'ALLOCATED', 'UNSTARTED_EXPIRED', 'ANOMALY', 'OVERDUE', 'VERIFIED_RELEASED',
        'EMERGENCY_APPROVED', 'EMERGENCY_REJECTED', 'QUEUE_REORDERED'
    )
);

ALTER TABLE inbox_messages DROP CONSTRAINT inbox_messages_transition_uk;
ALTER TABLE inbox_messages ADD CONSTRAINT inbox_messages_transition_recipient_uk UNIQUE (transition_seq, recipient_id);
