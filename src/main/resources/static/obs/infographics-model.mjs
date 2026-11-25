const uuid = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;
const invalid = () => { throw Error('Некорректные данные инфографики'); };
export function validateInfographics(value, matchId, origin = globalThis.location?.origin ?? 'https://localhost') {
  if (!value || value.matchId !== matchId || !uuid.test(matchId) || !Number.isFinite(Date.parse(value.serverNow)) ||
      typeof value.presentationRevision !== 'string' || !['PLANNED','IN_PROGRESS','FINISHED'].includes(value.status) ||
      !['ROSTER','HIDDEN'].includes(value.phase?.kind) ||
      (value.phase.kind === 'ROSTER' ? value.status !== 'IN_PROGRESS' || !Number.isFinite(Date.parse(value.phase.startedAt)) : value.phase.startedAt !== null) ||
      !value.settings || ['rosterPageSeconds','pointPageSeconds','leaderPageSeconds'].some(k => !Number.isInteger(value.settings[k]) || value.settings[k] < 2 || value.settings[k] > 15) ||
      !Array.isArray(value.teams) || value.teams.length !== 2 || new Set(value.teams.map(t=>t.teamId)).size !== 2) invalid();
  const identities = new Set();
  return {...value, teams:value.teams.map(team => {
    if (!uuid.test(team.teamId) || typeof team.name !== 'string' || !Array.isArray(team.roster)) invalid();
    return {...team, roster:team.roster.map(player => {
      if (!uuid.test(player.participantId) || identities.has(player.participantId) || typeof player.name !== 'string' ||
          (player.number !== null && (!Number.isSafeInteger(player.number) || player.number < 0))) invalid();
      identities.add(player.participantId);
      let photoUrl = null;
      if (typeof player.photoUrl === 'string') try {
        const url = new URL(player.photoUrl,origin);
        if (url.origin === origin && url.protocol === 'https:' && !url.username && !url.password && !url.hash) photoUrl=url.href;
      } catch {}
      return {...player,photoUrl};
    })};
  })};
}
// One finite pass, anchored to the server's persisted match start; polls never restart it.
export function rosterPage(snapshot, serverNow) {
  if (!snapshot || snapshot.phase.kind !== 'ROSTER') return null;
  const page = Math.floor(Math.max(0,serverNow-Date.parse(snapshot.phase.startedAt))/(snapshot.settings.rosterPageSeconds*1000));
  const pages = Math.max(...snapshot.teams.map(t=>Math.ceil(t.roster.length/5)));
  if (page >= pages) return null;
  return {...snapshot,page,teams:snapshot.teams.map(team => {
    const first = Math.max(0,Math.min(page,Math.ceil(team.roster.length/5)-1))*5;
    return {...team,total:team.roster.length,first,roster:team.roster.slice(first,first+5)};
  })};
}

export class RosterClock {
  constructor(now = () => performance.now()) { this.now = now; this.identity = null; }
  current() { return this.server + Math.max(0, this.now() - this.anchor); }
  accept(value) {
    const identity = value ? JSON.stringify([value.matchId,value.phase.kind,value.phase.startedAt]) : null;
    const received = value ? Date.parse(value.serverNow) : 0;
    this.server = identity !== null && identity === this.identity ? Math.max(this.current(),received) : received;
    this.anchor = this.now();
    this.identity = identity;
  }
}
