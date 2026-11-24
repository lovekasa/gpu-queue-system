CREATE TABLE gpus (
    id INTEGER PRIMARY KEY,
    model VARCHAR(32) NOT NULL
);

INSERT INTO gpus (id, model) VALUES
    (1, 'RTX 4090'), (2, 'RTX 4090'), (3, 'RTX 4090'), (4, 'RTX 4090'),
    (5, 'RTX 4090'), (6, 'RTX 4090'), (7, 'RTX 4090'), (8, 'RTX 4090'),
    (9, 'RTX 4090'), (10, 'RTX 4090');
