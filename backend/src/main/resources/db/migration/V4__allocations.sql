CREATE TABLE queue_items_v4 (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    gpu_id INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    CONSTRAINT queue_items_request_fk FOREIGN KEY (request_id) REFERENCES requests(id),
    CONSTRAINT queue_items_gpu_fk FOREIGN KEY (gpu_id) REFERENCES gpus(id),
    CONSTRAINT queue_items_status_check CHECK (status IN ('WAITING', 'ALLOCATED')),
    CONSTRAINT queue_items_request_gpu_uk UNIQUE (request_id, gpu_id),
    CONSTRAINT queue_items_id_gpu_uk UNIQUE (id, gpu_id)
);

INSERT INTO queue_items_v4 (id, request_id, gpu_id, status)
SELECT id, request_id, gpu_id, status FROM queue_items;

DROP TABLE queue_items;

ALTER TABLE queue_items_v4 RENAME TO queue_items;

CREATE INDEX queue_items_gpu_status_idx ON queue_items(status, gpu_id);

CREATE TABLE allocations (
    id UUID PRIMARY KEY,
    queue_item_id UUID NOT NULL,
    gpu_id INTEGER NOT NULL,
    allocated_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    CONSTRAINT allocations_queue_item_uk UNIQUE (queue_item_id),
    CONSTRAINT allocations_id_gpu_uk UNIQUE (id, gpu_id),
    CONSTRAINT allocations_queue_item_gpu_fk FOREIGN KEY (queue_item_id, gpu_id) REFERENCES queue_items(id, gpu_id),
    CONSTRAINT allocations_gpu_fk FOREIGN KEY (gpu_id) REFERENCES gpus(id),
    CONSTRAINT allocations_expires_check CHECK (expires_at > allocated_at)
);

CREATE TABLE current_allocations (
    gpu_id INTEGER PRIMARY KEY,
    allocation_id UUID NOT NULL,
    CONSTRAINT current_allocations_gpu_fk FOREIGN KEY (gpu_id) REFERENCES gpus(id),
    CONSTRAINT current_allocations_allocation_uk UNIQUE (allocation_id),
    CONSTRAINT current_allocations_allocation_gpu_fk FOREIGN KEY (allocation_id, gpu_id) REFERENCES allocations(id, gpu_id)
);

CREATE TABLE audit_logs (
    transition_seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_account_id UUID,
    action VARCHAR(32) NOT NULL,
    request_id UUID NOT NULL,
    queue_item_id UUID NOT NULL,
    allocation_id UUID NOT NULL,
    gpu_id INTEGER NOT NULL,
    reason VARCHAR(500),
    before_state VARCHAR(255) NOT NULL,
    after_state VARCHAR(255) NOT NULL,
    CONSTRAINT audit_logs_actor_type_check CHECK (actor_type IN ('SYSTEM', 'MEMBER', 'ADMIN')),
    CONSTRAINT audit_logs_actor_account_fk FOREIGN KEY (actor_account_id) REFERENCES accounts(id),
    CONSTRAINT audit_logs_actor_consistency_check CHECK (
        (actor_type = 'SYSTEM' AND actor_account_id IS NULL)
        OR (actor_type IN ('MEMBER', 'ADMIN') AND actor_account_id IS NOT NULL)
    ),
    CONSTRAINT audit_logs_action_check CHECK (action IN ('ALLOCATED')),
    CONSTRAINT audit_logs_request_fk FOREIGN KEY (request_id) REFERENCES requests(id),
    CONSTRAINT audit_logs_queue_item_gpu_fk FOREIGN KEY (queue_item_id, gpu_id) REFERENCES queue_items(id, gpu_id),
    CONSTRAINT audit_logs_allocation_gpu_fk FOREIGN KEY (allocation_id, gpu_id) REFERENCES allocations(id, gpu_id),
    CONSTRAINT audit_logs_allocation_action_uk UNIQUE (allocation_id, action)
);

CREATE TABLE inbox_messages (
    id UUID PRIMARY KEY,
    recipient_id UUID NOT NULL,
    transition_seq BIGINT NOT NULL,
    allocation_id UUID NOT NULL,
    request_id UUID NOT NULL,
    gpu_id INTEGER NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    CONSTRAINT inbox_messages_recipient_fk FOREIGN KEY (recipient_id) REFERENCES accounts(id),
    CONSTRAINT inbox_messages_transition_fk FOREIGN KEY (transition_seq) REFERENCES audit_logs(transition_seq),
    CONSTRAINT inbox_messages_transition_uk UNIQUE (transition_seq),
    CONSTRAINT inbox_messages_allocation_uk UNIQUE (allocation_id),
    CONSTRAINT inbox_messages_allocation_gpu_fk FOREIGN KEY (allocation_id, gpu_id) REFERENCES allocations(id, gpu_id),
    CONSTRAINT inbox_messages_request_fk FOREIGN KEY (request_id) REFERENCES requests(id),
    CONSTRAINT inbox_messages_gpu_fk FOREIGN KEY (gpu_id) REFERENCES gpus(id),
    CONSTRAINT inbox_messages_type_check CHECK (message_type IN ('ALLOCATED'))
);

CREATE INDEX inbox_messages_recipient_seq_idx ON inbox_messages(recipient_id, transition_seq DESC);
