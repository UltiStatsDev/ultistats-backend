DROP TABLE IF EXISTS events;
DROP TABLE IF EXISTS match_participants;
DROP TABLE IF EXISTS match_teams;
DROP TABLE IF EXISTS team_players;
DROP TABLE IF EXISTS matches;
DROP TABLE IF EXISTS players;
DROP TABLE IF EXISTS teams;

CREATE TABLE teams (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    city VARCHAR(127),
    photo_url VARCHAR(1024),
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE players (
    id UUID PRIMARY KEY,
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255) NOT NULL,
    photo_url VARCHAR(1024),
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE team_players (
    team_id UUID NOT NULL REFERENCES teams(id),
    player_id UUID NOT NULL REFERENCES players(id),
    number INTEGER,
    deleted_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (team_id, player_id),
    UNIQUE (team_id, number),
    CHECK (number IS NULL OR number >= 0)
);

CREATE INDEX idx_team_players_player_id ON team_players(player_id);

CREATE TABLE matches (
    id UUID PRIMARY KEY,
    planned_start_timestamp TIMESTAMP WITH TIME ZONE,
    players_per_team INTEGER NOT NULL CHECK (players_per_team >= 2),
    started_at TIMESTAMP WITH TIME ZONE,
    ended_at TIMESTAMP WITH TIME ZONE,
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE match_teams (
    match_id UUID NOT NULL REFERENCES matches(id),
    team_id UUID NOT NULL REFERENCES teams(id),
    team_name VARCHAR(255) NOT NULL,
    position INTEGER NOT NULL,
    score INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (match_id, team_id),
    UNIQUE (match_id, position),
    CHECK (position IN (1, 2)),
    CHECK (score >= 0)
);

CREATE INDEX idx_match_teams_team_id_match_id ON match_teams(team_id, match_id);

CREATE TABLE match_participants (
    match_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    team_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL,
    unknown_slot INTEGER,
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    number INTEGER,
    PRIMARY KEY (match_id, participant_id),
    FOREIGN KEY (match_id, team_id) REFERENCES match_teams(match_id, team_id),
    UNIQUE (match_id, team_id, unknown_slot),
    UNIQUE (match_id, team_id, number),
    CHECK (number IS NULL OR number >= 0),
    CHECK (
        (kind = 'PLAYER' AND unknown_slot IS NULL)
        OR
        (kind = 'UNKNOWN' AND unknown_slot IN (1, 2) AND number IS NULL)
    ),
    CHECK (
        (kind = 'PLAYER' AND first_name IS NOT NULL AND last_name IS NOT NULL)
        OR
        (kind = 'UNKNOWN' AND first_name IS NULL AND last_name IS NULL)
    )
);

CREATE TABLE events (
    id UUID PRIMARY KEY,
    match_id UUID NOT NULL REFERENCES matches(id),
    sequence_number INTEGER NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    from_participant_id UUID,
    to_participant_id UUID,
    team_id UUID,
    deleted_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (match_id, sequence_number),
    FOREIGN KEY (match_id, from_participant_id) REFERENCES match_participants(match_id, participant_id),
    FOREIGN KEY (match_id, to_participant_id) REFERENCES match_participants(match_id, participant_id),
    FOREIGN KEY (match_id, team_id) REFERENCES match_teams(match_id, team_id),
    CHECK (sequence_number > 0)
);

CREATE INDEX idx_matches_started_at ON matches(started_at);
