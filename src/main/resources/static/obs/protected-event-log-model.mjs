import { category } from "./feed.mjs";
const record = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
const uuid = (v) => typeof v === "string" && /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(v);
const color = (v) => typeof v === "string" && /^#[0-9a-f]{6}$/i.test(v);
const date = (v) => typeof v === "string" && Number.isFinite(Date.parse(v));
const integer = (v, min, max = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(v) && v >= min && v <= max;
const fail = () => {
  throw Error("Некорректные данные журнала");
};
export function validateEventLog(value, matchId) {
  if (
    !record(value) ||
    !uuid(value.matchId) ||
    value.matchId !== matchId ||
    !date(value.serverNow) ||
    !integer(value.logRevision, 0) ||
    !Array.isArray(value.teams) ||
    value.teams.length !== 2
  )
    fail();
  const ids = value.teams.map((team) => team.teamId);
  if (
    new Set(ids).size !== 2 ||
    value.teams.some((team) => !uuid(team.teamId) || typeof team.teamName !== "string" || !integer(team.score, 0))
  )
    fail();
  const c = value.config;
  if (
    !record(c) ||
    !Array.isArray(c.categories) ||
    c.categories.some((v) => !["GOAL", "BLOCK", "INTERCEPTION", "CALLAHAN"].includes(v)) ||
    !integer(c.maxRows, 1, 20) ||
    !integer(c.ttlSeconds, 1, 60) ||
    !["TOP_RIGHT", "TOP_LEFT", "BOTTOM_RIGHT", "BOTTOM_LEFT"].includes(c.corner) ||
    !color(c.backgroundColor) ||
    !color(c.textColor) ||
    !record(c.teamColors) ||
    Object.entries(c.teamColors).some(([id, v]) => !ids.includes(id) || !color(v))
  )
    fail();
  const validEvent = (event) =>
    record(event) &&
    uuid(event.id) &&
    integer(event.sequenceNumber, 0) &&
    typeof event.type === "string" &&
    date(event.occurredAt) &&
    (!category(event.type) ||
      (uuid(event.fromSlotId ?? event.fromParticipantId) && uuid(event.toSlotId ?? event.toParticipantId))) &&
    ["participantId", "fromParticipantId", "toParticipantId", "fromSlotId", "toSlotId", "teamId"].every(
      (key) => event[key] == null || uuid(event[key]),
    );
  if (
    !Array.isArray(value.events) ||
    value.events.some((e) => !validEvent(e)) ||
    !Array.isArray(value.retractions) ||
    value.retractions.some((r) => !record(r) || !validEvent(r.event) || !date(r.cancelledAt)) ||
    !Array.isArray(value.participants) ||
    value.participants.some(
      (p) =>
        !record(p) ||
        !uuid(p.participantId) ||
        !ids.includes(p.teamId) ||
        !["PLAYER", "UNKNOWN"].includes(p.kind) ||
        [p.firstName, p.lastName].some((name) => name !== null && typeof name !== "string") ||
        (p.number !== null && !integer(p.number, 0)),
    ) ||
    !Array.isArray(value.temporarySlots) ||
    value.temporarySlots.some(
      (s) =>
        !record(s) ||
        !uuid(s.slotId) ||
        !uuid(s.pointId) ||
        !ids.includes(s.teamId) ||
        !integer(s.number, 0) ||
        (s.resolvedParticipantId !== null && !uuid(s.resolvedParticipantId)),
    )
  )
    fail();
  return value;
}
export function eventLogMatch(snapshot) {
  const participants = new Map(snapshot.participants.map((p) => [p.participantId, p]));
  for (const slot of snapshot.temporarySlots) {
    // Canonical EventResponse uses the slot UUID in the participant reference fields.
    const resolved = participants.get(slot.resolvedParticipantId);
    participants.set(slot.slotId, { ...(resolved ?? { kind: "UNKNOWN", teamId: slot.teamId }), participantId: slot.slotId });
  }
  return {
    teams: snapshot.teams.map((team) => ({
      ...team,
      participants: [...participants.values()].filter((p) => p.teamId === team.teamId),
    })),
  };
}
