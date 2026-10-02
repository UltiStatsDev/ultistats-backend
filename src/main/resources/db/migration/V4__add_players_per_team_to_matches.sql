ALTER TABLE matches
    ADD COLUMN players_per_team INTEGER NOT NULL DEFAULT 7;

ALTER TABLE matches
    ADD CONSTRAINT chk_matches_players_per_team CHECK (players_per_team >= 2);
