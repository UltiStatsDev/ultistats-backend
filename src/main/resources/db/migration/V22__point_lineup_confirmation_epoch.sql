-- Existing rows and older writers intentionally retain NULL; no historical confirmation is invented.
ALTER TABLE match_point_lineups ADD COLUMN confirmed_at TIMESTAMPTZ;
