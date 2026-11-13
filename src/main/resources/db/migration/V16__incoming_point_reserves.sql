ALTER TABLE events ADD CONSTRAINT uq_events_match_id_id_to_slot UNIQUE (match_id, id, to_slot_id);

ALTER TABLE match_point_lineup_entries
    ADD COLUMN introduced_by_event_id UUID,
    ADD CONSTRAINT uq_lineup_entries_introduction UNIQUE (match_id, introduced_by_event_id),
    ADD CONSTRAINT fk_lineup_entries_introduction FOREIGN KEY (match_id, introduced_by_event_id, slot_id)
        REFERENCES events(match_id, id, to_slot_id),
    ADD CONSTRAINT chk_lineup_entries_introduction_slot CHECK (introduced_by_event_id IS NULL OR slot_id IS NOT NULL);
