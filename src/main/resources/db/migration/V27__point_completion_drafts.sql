CREATE TABLE match_point_completion_drafts (
    match_id UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    point_id UUID NOT NULL,
    closing_event_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    base_log_revision BIGINT NOT NULL CHECK (base_log_revision >= 0),
    base_lineup_revision BIGINT CHECK (base_lineup_revision >= 0),
    insertions TEXT NOT NULL,
    PRIMARY KEY (match_id, point_id)
);
