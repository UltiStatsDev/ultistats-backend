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
  assert.equal(row.right.label, "Запасной №8");
  assert.equal(row.right.teamId, other);
  value.temporarySlots[0].resolvedParticipantId = id;
  row = resolveRow(value.events[0], eventLogMatch(value));
  assert.equal(row.right.label, "Запасной №8", "opposing team PLAYER cannot resolve this slot");
  assert.equal(row.right.teamId, other);
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

const instant = ms => new Date(Date.parse("2026-01-01T00:00:00Z") + ms).toISOString();
function protectedFixture(config = {}) {
  let now = 0, revision = 0;
  const feed = new Feed({ ...defaults, ...config }, () => now, { protected: true });
  const reconcile = (events = [], retractions = [], server = now, matchId = id) => feed.reconcile({
    matchId, events, retractions, logRevision: ++revision, serverNow: instant(server),
  });
  return { feed, reconcile, time: value => now = value };
}
test("protected original and separate cancellation keep independent deadlines at maxRows1", () => {
  const {feed, reconcile, time} = protectedFixture({maxRows:1});
  reconcile(); reconcile([event(1)]);
  const original = feed.rows()[0];
  time(3000); const cancel = {event:event(1),cancelledAt:instant(3000)};
  reconcile([], [cancel]);
  assert.deepEqual(feed.rows().map(r=>r.kind), ['cancellation','original']);
  assert.notEqual(feed.rows()[0].key, original.key);
  assert.equal(feed.rows()[1].deadline, original.deadline);
  time(8000); assert.deepEqual(feed.rows().map(r=>r.kind), ['cancellation']);
  reconcile([], [cancel]); assert.equal(feed.rows().length,1);
  time(11000); assert.equal(feed.rows().length,0);
  reconcile([], [cancel]); assert.equal(feed.rows().length,0);
});
test("fresh/reloaded protected baseline shows only unexpired confirmed cancellations without original", () => {
  const {feed,reconcile,time} = protectedFixture();
  time(10000); reconcile([event(1)], [
    {event:event(2),cancelledAt:instant(0)}, {event:event(3),cancelledAt:instant(6000)},
  ]);
  assert.deepEqual(feed.rows().map(r=>[r.event.id,r.kind]), [[event(3).id,'cancellation']]);
  time(14000); assert.equal(feed.rows().length,0);
});
test("protected server time uses monotonic progression and cannot rewind on repeat/reconnect", () => {
  const {feed,reconcile,time} = protectedFixture();
  const cancel={event:event(1),cancelledAt:instant(0)};
  reconcile([], [cancel], 4000);
  time(3000); reconcile([], [cancel], 1000);
  assert.equal(feed.rows().length,1);
  time(4000); assert.equal(feed.rows().length,0);
  time(5000); reconcile([], [cancel], 0); assert.equal(feed.rows().length,0);
});
test("protected categories and logical limits prune both rows permanently; edits preserve original deadline", () => {
  const {feed,reconcile,time}=protectedFixture({maxRows:1});
  reconcile(); reconcile([event(1)]); const deadline=feed.rows()[0].deadline;
  time(1000); reconcile([{...event(1),type:'GOAL'}]);
  assert.equal(feed.rows()[0].deadline,deadline);
  reconcile([], [{event:event(1),cancelledAt:instant(1000)}]);
  feed.configure({...defaults,maxRows:1,categories:['GOAL']}); assert.equal(feed.rows().length,1);
  feed.configure({...defaults,maxRows:1});
  reconcile([event(2)], [{event:event(1),cancelledAt:instant(1000)}]);
  assert.deepEqual(feed.rows().map(r=>r.event.id),[event(2).id]);
  reconcile([], [{event:event(1),cancelledAt:instant(1000)}]); assert.equal(feed.rows().length,0);
});
test("protected match identity resets baseline and cancellation identities across A B A", () => {
  const {feed,reconcile}=protectedFixture();
  const cancel={event:event(1),cancelledAt:instant(0)};
  reconcile([], [cancel]); const key=feed.rows()[0].key;
  reconcile([event(2)], [], 0, other); assert.equal(feed.rows().length,0);
  reconcile([event(2)], [cancel]); assert.equal(feed.rows().length,1);
  assert.notEqual(feed.rows()[0].key,key);
});
test("protected lifetimes ignore local wall-clock jumps and config/retraction repeats never refresh deadlines", () => {
  const {feed,reconcile,time}=protectedFixture({ttlSeconds:3});
  const wall=Date.now;
  try {
    reconcile(); reconcile([event(1)]);
    const deadline=feed.rows()[0].deadline;
    Date.now=()=>9999999999999;
    time(1000); reconcile([], [{event:event(1),cancelledAt:instant(1000)}]);
    const cancellation=feed.rows()[0].deadline;
    feed.configure({...defaults,ttlSeconds:60});
    Date.now=()=>0;
    time(2000); reconcile([], [{event:event(1),cancelledAt:instant(1000)}]);
    assert.equal(feed.rows()[0].deadline,cancellation);
    assert.equal(feed.rows()[1].deadline,deadline);
    time(3000); assert.deepEqual(feed.rows().map(r=>r.kind),['cancellation']);
    time(4000); assert.equal(feed.rows().length,0);
  } finally {Date.now=wall;}
});

test("team results preserve the protected source and never create player cards", () => {
  const snapshot = sample();
  snapshot.events = [];
  let now = 0;
  const feed = new Feed(snapshot.config, () => now, { protected: true });
  const update = () => {
    const next = { ...snapshot, logRevision: ++snapshot.logRevision, serverNow: instant(now) };
    feed.reconcile(validateEventLog(next, id));
  };
  update();
  snapshot.events.push(event(1, "GOAL"));
  update();
  const original = feed.rows()[0];
  now = 1000;
  const marker = {
    id: event(2).id,
    sequenceNumber: 2,
    type: "POINT_RESULT",
    occurredAt: instant(now),
    teamId: id,
  };
  snapshot.events.push(marker);
  snapshot.teams[0].score++;
  update();
  assert.deepEqual(feed.rows(), [original]);
  now = 2000;
  snapshot.events.push(event(3, "CALLAHAN"));
  update();
  assert.deepEqual(feed.rows().map(row => row.event.type), ["CALLAHAN", "GOAL"]);
});
