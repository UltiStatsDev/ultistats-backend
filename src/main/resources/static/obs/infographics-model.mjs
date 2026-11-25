const uuid = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;
const invalid = () => { throw Error('Некорректные данные инфографики'); };
export function validateInfographics(value, matchId, origin = globalThis.location?.origin ?? 'https://localhost') {
  if (!value || value.matchId !== matchId || !uuid.test(matchId) || !Number.isFinite(Date.parse(value.serverNow)) ||
      typeof value.presentationRevision !== 'string' || !['PLANNED','IN_PROGRESS','FINISHED'].includes(value.status) ||
      !['ROSTER','LINEUP','HIDDEN'].includes(value.phase?.kind) ||
      (value.phase.kind !== 'HIDDEN' ? value.status !== 'IN_PROGRESS' || !Number.isFinite(Date.parse(value.phase.startedAt)) : value.phase.startedAt !== null) ||
      !value.settings || ['rosterPageSeconds','pointPageSeconds','leaderPageSeconds'].some(k => !Number.isInteger(value.settings[k]) || value.settings[k] < 2 || value.settings[k] > 15) ||
      !Array.isArray(value.teams) || value.teams.length !== 2 || new Set(value.teams.map(t=>t.teamId)).size !== 2) invalid();
  if (value.phase.lineupStartedAt != null && (!Number.isFinite(Date.parse(value.phase.lineupStartedAt)) ||
      !uuid.test(value.phase.pointId) || !Number.isSafeInteger(value.phase.lineupRevision) ||
      !['CONFIRMED','LEGACY_POINT_OPEN'].includes(value.phase.anchorKind))) invalid();
  if (value.phase.kind === 'LINEUP' && value.phase.lineupStartedAt == null) invalid();
  if (value.phase.pageSeconds != null && (!Number.isInteger(value.phase.pageSeconds) || value.phase.pageSeconds < 2 || value.phase.pageSeconds > 15)) invalid();
  if (value.phase.lineupPageSeconds != null && (!Number.isInteger(value.phase.lineupPageSeconds) || value.phase.lineupPageSeconds < 2 || value.phase.lineupPageSeconds > 15)) invalid();
  const identities = new Set();
  return {...value, teams:value.teams.map(team => {
    if (!uuid.test(team.teamId) || typeof team.name !== 'string' || !Array.isArray(team.roster)) invalid();
    const players = (entries, selected = false) => entries.map(player => {
      if (!uuid.test(player.participantId) || (!selected && identities.has(player.participantId)) || typeof player.name !== 'string' ||
          (player.number !== null && (!Number.isSafeInteger(player.number) || player.number < 0))) invalid();
      if (!selected) identities.add(player.participantId);
      if (player.temporary === true && (player.name !== 'Игрок неизвестен' || player.number !== null || player.photoUrl !== null)) invalid();
      let photoUrl = null;
      if (typeof player.photoUrl === 'string') try {
        const url = new URL(player.photoUrl,origin);
        if (url.origin === origin && url.protocol === 'https:' && !url.username && !url.password && !url.hash) photoUrl=url.href;
      } catch {}
      return {...player,photoUrl};
    });
    if (team.places != null && !Array.isArray(team.places)) invalid();
    const places = players(team.places ?? [], true);
    if (new Set(places.map(p=>p.participantId)).size !== places.length) invalid();
    return {...team,roster:players(team.roster),places};
  })};
}
// Stable phase epochs govern both polls and reconnects; each team holds its last page within a cycle.
export function rosterPage(snapshot, serverNow) {
  if (!snapshot || snapshot.phase.kind === 'HIDDEN') return null;
  const lineup = snapshot.phase.lineupStartedAt != null && serverNow >= Date.parse(snapshot.phase.lineupStartedAt);
  const kind = lineup ? 'LINEUP' : snapshot.phase.kind;
  const startedAt = lineup ? snapshot.phase.lineupStartedAt : snapshot.phase.startedAt;
  const seconds = lineup ? (snapshot.phase.lineupPageSeconds ?? snapshot.phase.pageSeconds ?? snapshot.settings.pointPageSeconds) : (snapshot.phase.pageSeconds ?? snapshot.settings.rosterPageSeconds);
  const teams = snapshot.teams.map(t=>({...t,roster:lineup ? t.places : t.roster}));
  const pages = Math.max(1,...teams.map(t=>Math.ceil(t.roster.length/5)));
  const page = Math.floor(Math.max(0,serverNow-Date.parse(startedAt))/(seconds*1000)) % pages;
  return {...snapshot,phase:{...snapshot.phase,kind},page,teams:teams.map(team => {
    const first = Math.max(0,Math.min(page,Math.ceil(team.roster.length/5)-1))*5;
    return {...team,total:team.roster.length,first,roster:team.roster.slice(first,first+5)};
  })};
}

export class RosterClock {
  constructor(now = () => performance.now()) { this.now = now; this.identity = null; }
  current() { return this.server + Math.max(0, this.now() - this.anchor); }
  accept(value) {
    const identity = value ? value.matchId : null;
    const received = value ? Date.parse(value.serverNow) : 0;
    if (identity !== null && identity === this.identity && received < this.received) return false;
    this.received = received;
    this.server = identity !== null && identity === this.identity ? Math.max(this.current(),received) : received;
    this.anchor = this.now();
    this.identity = identity;
    return true;
  }
}
