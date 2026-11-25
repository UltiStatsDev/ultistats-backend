CREATE TABLE obs_infographics_presentations (
    generation UUID PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE,
    binding VARCHAR(36) NOT NULL,
    ordinal BIGINT NOT NULL CHECK (ordinal > 0),
    prior_generation UUID,
    match_id UUID NOT NULL,
    input_revision VARCHAR(129) NOT NULL,
    settings_revision BIGINT NOT NULL CHECK (settings_revision >= 0),
    started_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > started_at),
    page_seconds INTEGER NOT NULL CHECK (page_seconds BETWEEN 2 AND 15),
    teams_json TEXT NOT NULL,
    hidden BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (binding, ordinal)
);
