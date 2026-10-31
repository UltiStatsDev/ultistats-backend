CREATE TABLE undo_command_receipts (
    id UUID PRIMARY KEY,
    match_id UUID NOT NULL REFERENCES matches(id),
    command_id UUID NOT NULL,
    expected_last_event_id UUID NOT NULL,
    expected_log_revision BIGINT NOT NULL CHECK (expected_log_revision >= 0),
    result_json TEXT NOT NULL,
    UNIQUE (match_id, command_id)
);
