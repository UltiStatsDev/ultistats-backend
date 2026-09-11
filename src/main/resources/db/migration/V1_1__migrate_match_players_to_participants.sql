ALTER TABLE match_players RENAME TO match_participants;
ALTER TABLE match_participants RENAME COLUMN player_id TO participant_id;

ALTER TABLE match_participants DROP CONSTRAINT match_players_player_id_fkey;
ALTER TABLE match_participants ADD COLUMN kind VARCHAR(16);
ALTER TABLE match_participants ADD COLUMN unknown_slot INTEGER;

UPDATE match_participants SET kind = 'PLAYER';

ALTER TABLE match_participants ALTER COLUMN kind SET NOT NULL;
ALTER TABLE match_participants
    ADD CONSTRAINT uq_match_participants_unknown_slot
    UNIQUE (match_id, team_id, unknown_slot);
ALTER TABLE match_participants
    ADD CONSTRAINT chk_match_participant_kind
    CHECK (
        (kind = 'PLAYER' AND unknown_slot IS NULL)
        OR
        (kind = 'UNKNOWN' AND unknown_slot IN (1, 2) AND number IS NULL)
    );

ALTER TABLE events RENAME COLUMN from_player_id TO from_participant_id;
ALTER TABLE events RENAME COLUMN to_player_id TO to_participant_id;
