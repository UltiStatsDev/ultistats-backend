import { test } from "node:test";
import assert from "node:assert/strict";
import { parseSource } from "../../main/resources/static/obs/protected-source-client.mjs";
import { validateEventLog, eventLogMatch } from "../../main/resources/static/obs/protected-event-log-model.mjs";
import { Feed, defaults, resolveRow } from "../../main/resources/static/obs/feed.mjs";
const id = "abcdefab-cdef-abcd-efab-cdefabcdefab",
  other = "ffffffff-ffff-ffff-ffff-ffffffffffff";
const event = (n, type = "BLOCK_FIELD") => ({
  id: `00000000-0000-0000-0000-${String(n).padStart(12, "0")}`,
  sequenceNumber: n,
  type,
  occurredAt: "2026-01-01T00:00:00Z",
  fromParticipantId: id,
  toParticipantId: other,
});
export const sample = () => ({
  matchId: id,
  teams: [
    { teamId: id, teamName: "A", score: 0 },
    { teamId: other, teamName: "B", score: 1 },
  ],
  config: { ...defaults, teamColors: { [id]: "#ff0000" } },
  logRevision: 1,
  serverNow: "2026-01-01T00:00:00Z",
  events: [event(1)],
  retractions: [],
  participants: [
    { participantId: id, teamId: id, kind: "PLAYER", firstName: "Иван", lastName: "Исторический", number: 7, unknownSlot: null },
  ],
  temporarySlots: [{ slotId: other, pointId: id, teamId: other, number: 8, resolvedParticipantId: null }],
});
test("explicit event-log source, scoreboard default preserved", () => {
  const link = new URL(`https://localhost/obs-sources/event-log.html?mode=auto#key=${"a".repeat(43)}`);
  assert.equal(parseSource(link, "event-log").path, "/api/v1/obs/sources/event-log/auto");
  assert.throws(() => parseSource(link));
  assert.throws(() => parseSource(link, "other"));
});
test("snapshot preserves canonical non-display events and optional legacy photos; scoped config is mandatory", () => {
  const value = sample();
  value.events.push(event(2, "ANNOUNCEMENT_START"));
  value.teams[0].photoUrl = "https://legacy.invalid/photo";
  assert.equal(validateEventLog(value, id), value);
  for (const bad of [
    { ...value, matchId: other },
    { ...value, config: null },
    { ...value, events: null },
    { ...value, participants: [], temporarySlots: null },
    { ...value, serverNow: "bad" },
    { ...value, config: { ...value.config, ttlSeconds: 0 } },
    { ...value, config: { ...value.config, teamColors: { outside: "#ff0000" } } },
  ])
    assert.throws(() => validateEventLog(bad, id));
});
test("historical actors and slot aliases preserve defensive left/right and raw payload", () => {
  const value = sample(),
    before = JSON.stringify(value);
  let row = resolveRow(value.events[0], eventLogMatch(value));
  assert.equal(row.left.label, "7 Исторический И.");
  assert.equal(row.right.label, "Неизвестный игрок");
  assert.equal(row.right.teamId, other);
  value.temporarySlots[0].resolvedParticipantId = id;
  row = resolveRow(value.events[0], eventLogMatch(value));
  assert.equal(row.right.label, row.left.label);
  value.temporarySlots[0].resolvedParticipantId = null;
  assert.equal(JSON.stringify(value), before);
});
test("ordinary baseline, updates, reconnect, categories and own TTL use existing Feed", () => {
  let now = 0;
  const value = sample(),
    feed = new Feed(value.config, () => now);
  feed.reconcile(value);
  assert.equal(feed.rows().length, 0);
  value.events.push(event(2));
  value.logRevision++;
  feed.reconcile(value);
  const shownAt = feed.rows()[0].shownAt;
  now = 4000;
  value.events[1] = { ...event(2), type: "INTERCEPTION" };
  value.logRevision++;
  feed.reconcile(value);
  assert.equal(feed.rows()[0].shownAt, shownAt);
  assert.equal(feed.rows()[0].event.type, "INTERCEPTION");
  feed.reconcile(value);
  assert.equal(feed.rows().length, 1);
  now = 8000;
  assert.equal(feed.rows().length, 0);
  feed.reconcile(value);
  assert.equal(feed.rows().length, 0);
  const fresh = new Feed(value.config);
  fresh.reconcile(value);
  assert.equal(fresh.rows().length, 0);
});
test("required displayed canonical actor pair and identity reject malformed events", () => {
  const value = sample();
  for (const patch of [{ id: "bad" }, { fromParticipantId: undefined }, { toParticipantId: undefined }]) {
    assert.throws(() => validateEventLog({ ...value, events: [{ ...value.events[0], ...patch }] }, id));
  }
});
