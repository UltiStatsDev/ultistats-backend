-- Preserve the roster foreign keys and give temporary actors their own match-scoped references.
ALTER TABLE events
    ADD COLUMN from_slot_id UUID,
    ADD COLUMN to_slot_id UUID,
    ADD CONSTRAINT fk_events_from_slot FOREIGN KEY (match_id, from_slot_id)
        REFERENCES match_point_lineup_entries(match_id, slot_id),
    ADD CONSTRAINT fk_events_to_slot FOREIGN KEY (match_id, to_slot_id)
        REFERENCES match_point_lineup_entries(match_id, slot_id),
    ADD CONSTRAINT chk_events_actor_source CHECK (
        (from_participant_id IS NULL OR from_slot_id IS NULL)
        AND (to_participant_id IS NULL OR to_slot_id IS NULL)
    );
