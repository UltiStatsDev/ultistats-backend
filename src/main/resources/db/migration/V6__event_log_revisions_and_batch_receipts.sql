ALTER TABLE matches ADD COLUMN event_log_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE matches ADD COLUMN last_event_log_invalidation_revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE event_batch_receipts (
    id UUID PRIMARY KEY,
    match_id UUID NOT NULL REFERENCES matches(id),
    base_sequence_number INTEGER NOT NULL CHECK (base_sequence_number >= 0),
    base_log_revision BIGINT NOT NULL CHECK (base_log_revision >= 0),
    fingerprint VARCHAR(64) NOT NULL,
    UNIQUE (match_id, base_sequence_number, base_log_revision)
);
