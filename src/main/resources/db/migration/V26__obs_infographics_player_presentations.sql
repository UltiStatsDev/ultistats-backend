-- A phase hide occupies the same ordered stream as a finite show but has no deadline.
ALTER TABLE obs_infographics_presentations
    ADD COLUMN phase_token VARCHAR(64),
    ALTER COLUMN expires_at DROP NOT NULL,
    ALTER COLUMN page_seconds DROP NOT NULL,
    ADD CONSTRAINT obs_infographics_presentations_command_shape CHECK (
        (phase_token IS NULL AND expires_at IS NOT NULL AND page_seconds IS NOT NULL) OR
        (phase_token IS NOT NULL AND expires_at IS NULL AND page_seconds IS NULL AND
            kind IN ('ROSTER', 'POINT') AND origin = 'MANUAL' AND hidden)
    );
