CREATE TABLE match_point_completion_approvals (
    match_id UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    point_id UUID NOT NULL,
    closing_event_id UUID NOT NULL,
    insertions TEXT NOT NULL,
    PRIMARY KEY (match_id, point_id)
);
