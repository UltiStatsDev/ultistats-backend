CREATE TABLE match_point_completion_approval_receipts (
    match_id UUID NOT NULL,
    point_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    expected_log_revision BIGINT NOT NULL CHECK (expected_log_revision >= 0),
    expected_lineup_revision BIGINT CHECK (expected_lineup_revision >= 0),
    draft_revision BIGINT NOT NULL CHECK (draft_revision > 0),
    log_revision BIGINT NOT NULL CHECK (log_revision = expected_log_revision + 1),
    last_sequence_number INTEGER NOT NULL CHECK (last_sequence_number > 0),
    PRIMARY KEY (match_id, operation_id),
    UNIQUE (match_id, point_id),
    FOREIGN KEY (match_id, point_id) REFERENCES match_point_completion_approvals(match_id, point_id) ON DELETE CASCADE
);
