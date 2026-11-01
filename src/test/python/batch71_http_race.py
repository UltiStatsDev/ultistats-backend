"""Actual HTTP/SSE crossed-UUID regression on an owned synthetic-only API.
Usage: python3 batch71_http_race.py http://127.0.0.1:PORT /tmp/OWN-proof.json
"""
import concurrent.futures
import http.client
import json
import pathlib
import socket
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
import uuid

base, output = sys.argv[1:]
origin = urllib.parse.urlsplit(base)

def req(path, method="GET", body=None):
    data = None if body is None else json.dumps(body).encode()
    try:
        response = urllib.request.urlopen(urllib.request.Request(base + path, data=data, method=method,
            headers={"Content-Type": "application/json"}), timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    data = response.read()
    return response.status, json.loads(data) if data else None

def frame(response):
    lines = []
    while True:
        line = response.readline()
        if not line:
            raise AssertionError("SSE closed unexpectedly")
        if line in [b"\n", b"\r\n"]:
            return b"".join(lines).decode()
        lines.append(line)

teams = []
for side in ["A", "B"]:
    status, team = req("/api/v1/teams", "POST", {"name": "Batch71 HTTP race " + side})
    assert status == 201
    teams.append(team["id"])
cases = []
for attempt in range(6):
    pair, connections, streams = [], [], []
    try:
        for _ in range(2):
            status, match = req("/api/v1/matches", "POST", {"teamIds": teams, "playersPerTeam": 2})
            assert status == 201
            mid = match["id"]
            assert req(f"/api/v1/matches/{mid}/start", "POST", {"timestamp": "2026-07-28T12:00:00Z"})[0] == 200
            pair.append(mid)
            connection = http.client.HTTPConnection(origin.hostname, origin.port, timeout=3)
            connection.request("GET", f"/api/v1/matches/{mid}/events/stream")
            response = connection.getresponse()
            assert response.status == 200
            assert "event:connected" in frame(response)
            connections.append(connection)
            streams.append(response)
        ids = [str(uuid.uuid4()), str(uuid.uuid4())]
        bodies = []
        for side in range(2):
            order = ids if side == 0 else ids[::-1]
            bodies.append({"expectedLastSequenceNumber": 0, "expectedLogRevision": 0,
                "events": [{"eventId": key, "type": kind, "occurredAt": "2026-07-28T12:00:00.123456789Z", "teamId": teams[0]}
                    for key, kind in zip(order, ["TIMEOUT_START", "TIMEOUT_END"])]})
        gate = threading.Barrier(2)
        def send(side):
            gate.wait(timeout=5)
            status, response = req(f"/api/v2/matches/{pair[side]}/events/batch", "POST", bodies[side])
            return {"matchId": pair[side], "body": bodies[side], "status": status, "response": response}
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(send, [0, 1]))
        assert sorted(r["status"] for r in results) == [201, 409], results
        for side, result in enumerate(results):
            mid = result["matchId"]
            status, log = req(f"/api/v2/matches/{mid}/events")
            assert status == 200
            winner = result["status"] == 201
            assert (len(log["events"]), log["lastSequenceNumber"], log["logRevision"]) == ((2, 2, 1) if winner else (0, 0, 0))
            assert all(team["teamScore"] == 0 for team in req(f"/api/v1/matches/{mid}")[1]["teams"])
            if winner:
                assert [e["id"] for e in log["events"]] == [e["eventId"] for e in bodies[side]["events"]]
                assert all(e["occurredAt"] == "2026-07-28T12:00:00.123456789Z" for e in log["events"])
                assert req(f"/api/v2/matches/{mid}/events/batch", "POST", bodies[side]) == (200, result["response"])
                notification = frame(streams[side])
                assert "event:event-log-changed" in notification
                assert bodies[side]["events"][-1]["eventId"] in notification
                result["notification"] = notification
            else:
                problem = result["response"]
                assert problem["code"] == "EVENT_IDEMPOTENCY_CONFLICT"
                assert (problem["lastSequenceNumber"], problem["logRevision"]) == (0, 0)
                assert bodies[side]["events"][problem["eventIndex"]]["eventId"] == problem["eventId"]
            # HTTP response completion follows the synchronous AFTER_COMMIT callback.
            # A replay must add no second winner notification; rollback adds none at all.
            connections[side].sock.settimeout(0.4)
            try:
                extra = frame(streams[side])
                assert "event:event-log-changed" not in extra, extra
            except (TimeoutError, socket.timeout):
                pass
            result["canonical"] = log
        cases.append({"attempt": attempt, "results": results})
        pathlib.Path(output).write_text(json.dumps(cases, indent=2))
        print(attempt, [r["status"] for r in results], "canonical/rollback/score/SSE-once PASS", flush=True)
    finally:
        for stream in streams:
            stream.close()
        for connection in connections:
            connection.close()
