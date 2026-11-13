CREATE TABLE obs_source_keys (
    key_id UUID PRIMARY KEY,
    match_id UUID NOT NULL REFERENCES matches(id),
    scope VARCHAR(32) NOT NULL CHECK (scope = 'EXACT_SCOREBOARD'),
    secret_digest VARCHAR(64) NOT NULL UNIQUE CHECK (CHAR_LENGTH(secret_digest) = 64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE
);
