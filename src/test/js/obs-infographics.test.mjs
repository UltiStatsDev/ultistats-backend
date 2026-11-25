import test from 'node:test';
import assert from 'node:assert/strict';
import { validateInfographics, rosterPage, RosterClock } from '../../main/resources/static/obs/infographics-model.mjs';
import { parseSource } from '../../main/resources/static/obs/protected-source-client.mjs';
import { parsePreview } from '../../main/resources/static/obs/staff-preview.mjs';
const id = n => `00000000-0000-0000-0000-${String(n).padStart(12,'0')}`;
const started = '2026-01-01T00:00:00Z';
const fixture = () => ({matchId:id(1), serverNow:'2026-01-01T00:00:06Z', presentationRevision:'revision', status:'IN_PROGRESS',
  phase:{kind:'ROSTER', startedAt:started}, settings:{rosterPageSeconds:5,pointPageSeconds:5,leaderPageSeconds:5},
  teams:[12,2].map((count,t) => ({teamId:id(t+2),name:'Team',roster:Array.from({length:count},(_,p) => ({participantId:id(100+t*100+p),name:`Player ${p}`,number:p,photoUrl:null}))}))});
test('new source and keyless preview accept only their typed exact or AUTO bindings', () => {
  const key = 'A'.repeat(43);
  assert.equal(parseSource(new URL(`https://localhost/obs-sources/infographics.html?mode=auto#key=${key}`),'infographics').matchId,null);
  assert.equal(parsePreview(new URL(`https://localhost/obs-preview/infographics.html?matchId=${id(1)}`),'infographics').matchId,id(1));
  assert.throws(() => parseSource(new URL(`https://localhost/obs-sources/scoreboard.html?mode=auto#key=${key}`),'infographics'));
  assert.throws(() => parsePreview(new URL(`https://localhost/obs-preview/infographics.html?mode=auto#key=${key}`),'infographics'));
});
test('server anchored five card pages hold the shorter side and end after one full pass', () => {
  const value=validateInfographics(fixture(),id(1));
  assert.deepEqual(rosterPage(value,Date.parse(value.serverNow)).teams.map(t=>t.roster.map(p=>p.number)),[[5,6,7,8,9],[0,1]]);
  assert.deepEqual(rosterPage(value,Date.parse(started)+11000).teams[0].roster.map(p=>p.number),[10,11]);
  assert.equal(rosterPage(value,Date.parse(started)+15000),null);
  assert.equal(rosterPage(value,Date.parse(started)+50000),null);
  assert.equal(rosterPage({...value,phase:{kind:'HIDDEN',startedAt:null}},Date.parse(started)),null);
});
test('snapshot rejects stale binding, invalid phase and duplicate identities; optional photos fall back', () => {
  assert.throws(()=>validateInfographics(fixture(),id(99)));
  const value=fixture();value.phase.startedAt='bad';
  assert.throws(()=>validateInfographics(value,id(1)));
  const duplicate=fixture();duplicate.teams[0].roster[1].participantId=duplicate.teams[0].roster[0].participantId;
  assert.throws(()=>validateInfographics(duplicate,id(1)));
  const photos=fixture();photos.teams[0].roster[0].photoUrl='https://elsewhere/photo';
  assert.equal(validateInfographics(photos,id(1),'https://localhost').teams[0].roster[0].photoUrl,null);
});

test('late repeated polls cannot rewind the current page; a new match resets the clock', () => {
  let monotonic = 0;
  const clock = new RosterClock(() => monotonic);
  const value = fixture();
  value.serverNow = '2026-01-01T00:00:04Z';
  clock.accept(value);
  monotonic = 1500;
  assert.equal(rosterPage(value, clock.current()).page, 1);
  clock.accept(value);
  assert.equal(rosterPage(value, clock.current()).page, 1);
  monotonic = 12000;
  clock.accept(value);
  assert.equal(rosterPage(value, clock.current()), null);
  clock.accept({...value, matchId:id(99),serverNow:'2026-01-01T00:00:01Z'});
  assert.equal(rosterPage(value, clock.current()).page, 0);
});
