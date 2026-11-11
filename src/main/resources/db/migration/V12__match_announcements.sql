CREATE TABLE match_announcements (
    match_id UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    id UUID NOT NULL,
    team_id UUID,
    started_second BIGINT NOT NULL,
    started_nano INTEGER NOT NULL CHECK (started_nano BETWEEN 0 AND 999999999),
    ended_second BIGINT,
    ended_nano INTEGER CHECK (ended_nano BETWEEN 0 AND 999999999),
    PRIMARY KEY (match_id, id),
    CHECK ((ended_second IS NULL) = (ended_nano IS NULL)),
    CHECK (ended_second > started_second OR (ended_second = started_second AND ended_nano >= started_nano))
);
