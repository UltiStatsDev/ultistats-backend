ALTER TABLE match_announcements
    ADD COLUMN original_team_id UUID,
    ADD COLUMN attribution_revision BIGINT NOT NULL DEFAULT 0 CHECK (attribution_revision >= 0);
