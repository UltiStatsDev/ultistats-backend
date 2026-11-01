-- Additive: previous binaries still read/write occurred_at. Legacy precision is unknown.
-- Leave existing rows NULL; immutable request fingerprints are not reversible timestamps.
ALTER TABLE events ADD COLUMN original_occurred_at VARCHAR(40);
-- Keep lifecycle boundaries in the same precision as the events they constrain.
ALTER TABLE matches ADD COLUMN original_started_at VARCHAR(40);
ALTER TABLE matches ADD COLUMN original_ended_at VARCHAR(40);
