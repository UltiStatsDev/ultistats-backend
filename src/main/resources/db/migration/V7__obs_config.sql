CREATE TABLE obs_config (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    payload TEXT NOT NULL
);
INSERT INTO obs_config (id, payload) VALUES (1, '{}');
