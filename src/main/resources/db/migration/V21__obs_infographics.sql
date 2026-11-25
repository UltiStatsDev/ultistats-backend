ALTER TABLE obs_source_keys
    DROP CONSTRAINT obs_source_keys_scope_check,
    DROP CONSTRAINT obs_source_keys_binding_check,
    ADD CONSTRAINT obs_source_keys_scope_check CHECK (
        scope IN ('EXACT_SCOREBOARD', 'EXACT_EVENT_LOG', 'AUTO_SCOREBOARD', 'AUTO_EVENT_LOG', 'EXACT_INFOGRAPHICS', 'AUTO_INFOGRAPHICS')
    ),
    ADD CONSTRAINT obs_source_keys_binding_check CHECK (
        (scope IN ('EXACT_SCOREBOARD', 'EXACT_EVENT_LOG', 'EXACT_INFOGRAPHICS') AND match_id IS NOT NULL) OR
        (scope IN ('AUTO_SCOREBOARD', 'AUTO_EVENT_LOG', 'AUTO_INFOGRAPHICS') AND match_id IS NULL)
    );
