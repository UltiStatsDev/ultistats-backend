ALTER TABLE obs_source_keys
    DROP CONSTRAINT obs_source_keys_scope_check,
    ADD CONSTRAINT obs_source_keys_scope_check CHECK (scope IN ('EXACT_SCOREBOARD', 'EXACT_EVENT_LOG'));

CREATE INDEX obs_event_retractions_window ON events (match_id, deleted_at, sequence_number)
    WHERE deleted_at IS NOT NULL;
