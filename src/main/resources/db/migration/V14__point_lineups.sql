CREATE TABLE match_point_lineups (
    match_id UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    point_id UUID NOT NULL,
    -- Keep the opening boundary even if it leaves the active journal.
    opening_event_id UUID,
    revision BIGINT NOT NULL CHECK (revision >= 1),
    PRIMARY KEY (match_id, point_id)
);

CREATE TABLE match_point_lineup_entries (
    match_id UUID NOT NULL,
    point_id UUID NOT NULL,
    team_id UUID NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0),
    participant_id UUID,
    slot_id UUID,
    slot_number INTEGER CHECK (slot_number > 0),
    resolved_participant_id UUID,
    PRIMARY KEY (match_id, point_id, team_id, position),
    FOREIGN KEY (match_id, point_id) REFERENCES match_point_lineups(match_id, point_id) ON DELETE CASCADE,
    FOREIGN KEY (match_id, team_id) REFERENCES match_teams(match_id, team_id),
    FOREIGN KEY (match_id, participant_id) REFERENCES match_participants(match_id, participant_id),
    FOREIGN KEY (match_id, resolved_participant_id) REFERENCES match_participants(match_id, participant_id),
    UNIQUE (match_id, point_id, participant_id),
    UNIQUE (match_id, slot_id),
    UNIQUE (match_id, point_id, team_id, slot_number),
    CHECK (
        (participant_id IS NOT NULL AND slot_id IS NULL AND slot_number IS NULL AND resolved_participant_id IS NULL)
        OR (participant_id IS NULL AND slot_id IS NOT NULL AND slot_number IS NOT NULL)
    )
);
