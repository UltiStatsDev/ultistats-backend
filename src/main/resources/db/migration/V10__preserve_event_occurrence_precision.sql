-- Additive: previous binaries still read/write occurred_at. Legacy precision is unknown.
-- Leave existing rows NULL; immutable request fingerprints are not reversible timestamps.
ALTER TABLE events ADD COLUMN original_occurred_at VARCHAR(40);
