CREATE TABLE requests (
    id UUID PRIMARY KEY,
    member_id UUID NOT NULL REFERENCES accounts(id),
    submitted_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    receive_seq BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL UNIQUE,
    duration_hours SMALLINT NOT NULL CHECK (duration_hours BETWEEN 1 AND 72)
);

CREATE INDEX requests_member_sequence ON requests(member_id, receive_seq DESC);

CREATE TABLE queue_items (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES requests(id),
    gpu_id INTEGER NOT NULL REFERENCES gpus(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('WAITING')),
    UNIQUE (request_id, gpu_id)
);
