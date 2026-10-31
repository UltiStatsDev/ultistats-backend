-- Presentation-only profiles; match lifecycle and accepted score remain in their existing tables.
CREATE TABLE obs_scoreboard_profiles (
    match_id UUID PRIMARY KEY REFERENCES matches(id),
    payload TEXT NOT NULL
);
