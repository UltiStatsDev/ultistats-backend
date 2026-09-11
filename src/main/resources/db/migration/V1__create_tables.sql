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
    CHECK (number IS NULL OR number >= 0)
);

CREATE UNIQUE INDEX uq_active_team_players_number
    ON team_players(team_id, number)
    WHERE deleted_at IS NULL AND number IS NOT NULL;

CREATE INDEX idx_team_players_player_id
    ON team_players(player_id);

CREATE TABLE matches (
    id UUID PRIMARY KEY,
    planned_start_timestamp TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    ended_at TIMESTAMP WITH TIME ZONE,
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE match_teams (
    match_id UUID NOT NULL REFERENCES matches(id),
    team_id UUID NOT NULL REFERENCES teams(id),
    position INTEGER NOT NULL,
    score INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (match_id, team_id),
    UNIQUE (match_id, position),
    CHECK (position IN (1, 2)),
    CHECK (score >= 0)
);

CREATE INDEX idx_match_teams_team_id_match_id
    ON match_teams(team_id, match_id);

CREATE TABLE match_players (
    match_id UUID NOT NULL,
    player_id UUID NOT NULL REFERENCES players(id),
    team_id UUID NOT NULL,
    number INTEGER,
    PRIMARY KEY (match_id, player_id),
    FOREIGN KEY (match_id, team_id) REFERENCES match_teams(match_id, team_id),
    UNIQUE (match_id, team_id, number),
    CHECK (number IS NULL OR number >= 0)
);

CREATE TABLE events (
    id UUID PRIMARY KEY,
    match_id UUID NOT NULL REFERENCES matches(id),
    sequence_number INTEGER NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    from_player_id UUID,
    to_player_id UUID,
    team_id UUID,
    deleted_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (match_id, sequence_number),
    FOREIGN KEY (match_id, from_player_id) REFERENCES match_players(match_id, player_id),
    FOREIGN KEY (match_id, to_player_id) REFERENCES match_players(match_id, player_id),
    FOREIGN KEY (match_id, team_id) REFERENCES match_teams(match_id, team_id),
    CHECK (sequence_number > 0)
);

CREATE INDEX idx_matches_started_at ON matches(started_at);
