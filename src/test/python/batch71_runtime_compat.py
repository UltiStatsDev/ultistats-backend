"""Synthetic-only actual HTTP rollback/restart proof. Run seed/new on the new binary,
old on the previous binary, against the SAME owned V10 DB. No raw user data export.
Usage: python3 batch71_runtime_compat.py seed|old|new http://127.0.0.1:PORT /tmp/OWN-proof.json
"""
import json
import pathlib
import sys
import urllib.error
import urllib.request
import uuid

mode, base, output = sys.argv[1:]
path = pathlib.Path(output)

def req(route, method="GET", body=None, expected=200):
    data = None if body is None else json.dumps(body).encode()
    try:
        response = urllib.request.urlopen(urllib.request.Request(
            base + route, data=data, method=method, headers={"Content-Type": "application/json"}), timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    raw = response.read()
    parsed = json.loads(raw) if raw else None
    assert response.status == expected, (route, response.status, parsed)
    return parsed

if mode == "seed":
    teams = [req("/api/v1/teams", "POST", {"name": "Batch71 compat " + side}, 201)["id"] for side in ["A", "B"]]
    match = req("/api/v1/matches", "POST", {"teamIds": teams, "playersPerTeam": 2}, 201)
    mid = match["id"]
    req(f"/api/v1/matches/{mid}/start", "POST", {"timestamp": "2026-07-28T12:00:00.123456789Z"})
    match = req(f"/api/v1/matches/{mid}")
    participant = match["teams"][0]["participants"][0]["participantId"]
    event = {"eventId": str(uuid.uuid4()), "type": "PULL", "occurredAt": "2026-07-28T12:00:00.123456789Z", "participantId": participant}
    body = {"expectedLastSequenceNumber": 0, "expectedLogRevision": 0, "events": [event]}
    created = req(f"/api/v2/matches/{mid}/events/batch", "POST", body, 201)
    assert created["events"][0]["occurredAt"] == event["occurredAt"]
    finished = req("/api/v1/matches", "POST", {"teamIds": teams, "playersPerTeam": 2}, 201)
    finished_id = finished["id"]
    req(f"/api/v1/matches/{finished_id}/start", "POST", {"timestamp": "2026-07-28T12:00:00.123456789Z"})
    finished = req(f"/api/v1/matches/{finished_id}")
    actors = finished["teams"][0]["participants"]
    complete = [dict(event, eventId=str(uuid.uuid4()), participantId=actors[0]["participantId"], type=kind) for kind in ["PULL", "PICKUP"]]
    complete.append({"eventId": str(uuid.uuid4()), "type": "GOAL", "occurredAt": "2026-07-28T12:00:00.999999789Z",
        "fromParticipantId": actors[0]["participantId"], "toParticipantId": actors[1]["participantId"]})
    req(f"/api/v2/matches/{finished_id}/events/batch", "POST", dict(body, events=complete), 201)
    req(f"/api/v1/matches/{finished_id}/end", "POST", {"timestamp": "2026-07-28T12:00:00.999999789Z"})
    state = {"matchId": mid, "participant": participant, "body": body, "created": created,
        "teamIds": teams, "finishedId": finished_id, "complete": complete}
else:
    state = json.loads(path.read_text())
    mid = state["matchId"]
    event = state["body"]["events"][0]
    if mode == "old":
        log = req(f"/api/v2/matches/{mid}/events")
        assert log["events"][0]["occurredAt"] == "2026-07-28T12:00:00.123457Z"
        replay = req(f"/api/v2/matches/{mid}/events/batch", "POST", state["body"])
        assert len(replay["events"]) == 1
        req(f"/api/v1/matches/{mid}/events/{event['eventId']}", "PATCH", {"type": "PULL", "participantId": state["participant"]})
        legacy = dict(event, eventId=str(uuid.uuid4()), type="PICKUP", occurredAt="2026-07-28T12:00:01.123456789Z")
        created = req(f"/api/v1/matches/{mid}/events", "POST", legacy, 201)
        assert created["occurredAt"] == "2026-07-28T12:00:01.123457Z"
        frozen = req(f"/api/v1/matches/{state['finishedId']}")
        assert frozen["startedAt"] == "2026-07-28T12:00:00.123457Z"
        assert frozen["endedAt"] == "2026-07-28T12:00:01Z"
        first = state["complete"][0]
        req(f"/api/v1/matches/{state['finishedId']}/events/{first['eventId']}", "PATCH",
            {"type": "PULL", "participantId": first["participantId"]})
        # Entirely old-writer lifecycle on V10: all unknown original metadata remains NULL.
        old_match = req("/api/v1/matches", "POST", {"teamIds": state["teamIds"], "playersPerTeam": 2}, 201)
        old_id = old_match["id"]
        old_at = "2026-07-28T12:00:00.123456123Z"
        req(f"/api/v1/matches/{old_id}/start", "POST", {"timestamp": old_at})
        actors = req(f"/api/v1/matches/{old_id}")["teams"][0]["participants"]
        old_events = [{"eventId": str(uuid.uuid4()), "type": kind, "occurredAt": old_at,
            **({"fromParticipantId": actors[0]["participantId"], "toParticipantId": actors[1]["participantId"]} if kind == "GOAL"
               else {"participantId": actors[0]["participantId"]})} for kind in ["PULL", "PICKUP", "GOAL"]]
        req(f"/api/v2/matches/{old_id}/events/batch", "POST", dict(state["body"], events=old_events), 201)
        req(f"/api/v1/matches/{old_id}/end", "POST", {"timestamp": old_at})
        state["oldLifecycleId"] = old_id
        state["oldInsert"] = legacy
        state["oldPosition"] = req(f"/api/v2/matches/{mid}/events")
    elif mode == "new":
        log = req(f"/api/v2/matches/{mid}/events")
        assert log["events"][0]["occurredAt"] == "2026-07-28T12:00:00.123456789Z", log
        assert log["events"][1]["occurredAt"] == "2026-07-28T12:00:01.123457Z", log
        assert [e["id"] for e in log["events"]] == [event["eventId"], state["oldInsert"]["eventId"]]
        assert (log["lastSequenceNumber"], log["logRevision"]) == (2, 3)
        conflict = req(f"/api/v2/matches/{mid}/events/batch", "POST", state["body"], 409)
        assert conflict["code"] == "EVENT_IDEMPOTENCY_CONFLICT"
        replay = req(f"/api/v1/matches/{mid}/events", "POST", state["oldInsert"])
        assert replay == log["events"][1]
        exact_match = req(f"/api/v1/matches/{mid}")
        assert exact_match["startedAt"] == "2026-07-28T12:00:00.123456789Z"
        finished_id = state["finishedId"]
        finished = req(f"/api/v1/matches/{finished_id}")
        assert finished["startedAt"] == "2026-07-28T12:00:00.123456789Z"
        assert finished["endedAt"] == "2026-07-28T12:00:00.999999789Z"
        portable = req("/api/v1/matches/export", "POST", {"matchIds": [finished_id]})
        imported = req("/api/v1/matches/import", "POST", portable)
        copy_id = imported["matchIds"][finished_id]
        copy = req("/api/v1/matches/export", "POST", {"matchIds": [copy_id]})["matches"][0]
        assert (copy["startedAt"], copy["endedAt"]) == (finished["startedAt"], finished["endedAt"])
        legacy_match = req(f"/api/v1/matches/{state['oldLifecycleId']}")
        assert legacy_match["startedAt"] == legacy_match["endedAt"] == "2026-07-28T12:00:00.123456Z"
        legacy_log = req(f"/api/v2/matches/{state['oldLifecycleId']}/events")
        assert all(e["occurredAt"] == "2026-07-28T12:00:00.123456Z" for e in legacy_log["events"])
        req("/api/v1/matches/export", "POST", {"matchIds": [state["oldLifecycleId"]]})
        state["newAfterRestart"] = log
        state["newBoundariesAfterRestart"] = {"precise": finished, "legacy": legacy_match}
    else:
        raise ValueError(mode)
path.write_text(json.dumps(state, indent=2))
print(mode, "PASS", state["matchId"])
