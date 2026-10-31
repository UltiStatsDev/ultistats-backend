DO $guard$
DECLARE before_count bigint;
BEGIN
 SELECT count(*) INTO before_count FROM undo_command_receipts WHERE match_id='93a9187e-8665-42d6-9fd1-545bf35a0068' AND command_id='441a1bc5-726f-4ea5-93d9-c708ff4d2187';
 IF before_count <> 1 THEN RAISE EXCEPTION 'Expected one owned receipt'; END IF;
 BEGIN
  INSERT INTO undo_command_receipts(id,match_id,command_id,expected_last_event_id,expected_log_revision,result_json)
   SELECT '020741f5-eb8f-4763-ad46-b4482a036f48',match_id,command_id,expected_last_event_id,expected_log_revision,result_json
   FROM undo_command_receipts WHERE match_id='93a9187e-8665-42d6-9fd1-545bf35a0068' AND command_id='441a1bc5-726f-4ea5-93d9-c708ff4d2187';
  RAISE EXCEPTION 'Duplicate receipt unexpectedly accepted';
 EXCEPTION WHEN unique_violation THEN
  RAISE NOTICE 'PASS: independent SQL writer cannot duplicate a command receipt';
 END;
 IF (SELECT count(*) FROM undo_command_receipts WHERE match_id='93a9187e-8665-42d6-9fd1-545bf35a0068' AND command_id='441a1bc5-726f-4ea5-93d9-c708ff4d2187') <> before_count
  THEN RAISE EXCEPTION 'Receipt count changed'; END IF;
END $guard$;
