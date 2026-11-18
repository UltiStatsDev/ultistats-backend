ALTER TABLE obs_source_keys
    ALTER COLUMN match_id DROP NOT NULL,
    DROP CONSTRAINT obs_source_keys_scope_check,
    ADD CONSTRAINT obs_source_keys_scope_check CHECK (
        scope IN ('EXACT_SCOREBOARD', 'EXACT_EVENT_LOG', 'AUTO_SCOREBOARD', 'AUTO_EVENT_LOG')
    ),
    ADD CONSTRAINT obs_source_keys_binding_check CHECK (
        (scope IN ('EXACT_SCOREBOARD', 'EXACT_EVENT_LOG') AND match_id IS NOT NULL) OR
        (scope IN ('AUTO_SCOREBOARD', 'AUTO_EVENT_LOG') AND match_id IS NULL)
    );

CREATE UNIQUE INDEX obs_source_keys_one_active_auto
    ON obs_source_keys (scope) WHERE revoked_at IS NULL AND match_id IS NULL;
