-- Building the unique index checks existing rows and fences concurrent legacy writers.
-- A duplicate aborts this transactional migration without choosing a surviving key.
CREATE UNIQUE INDEX obs_source_keys_one_active
    ON obs_source_keys (scope, match_id) WHERE revoked_at IS NULL;

ALTER TABLE obs_source_keys
    ADD COLUMN operation_id UUID UNIQUE,
    ADD COLUMN derivation_version SMALLINT,
    ADD CONSTRAINT obs_source_keys_derivation_pair CHECK (
        (operation_id IS NULL) = (derivation_version IS NULL)
    );

CREATE TABLE obs_source_key_material (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    fingerprint VARCHAR(64) CHECK (CHAR_LENGTH(fingerprint) = 64)
);
INSERT INTO obs_source_key_material (singleton) VALUES (1);
