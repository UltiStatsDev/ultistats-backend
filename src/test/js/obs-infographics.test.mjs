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
test('server anchored five card pages hold the shorter side and loop until confirmation', () => {
  const value=validateInfographics(fixture(),id(1));
  assert.deepEqual(rosterPage(value,Date.parse(value.serverNow)).teams.map(t=>t.roster.map(p=>p.number)),[[5,6,7,8,9],[0,1]]);
  assert.deepEqual(rosterPage(value,Date.parse(started)+11000).teams[0].roster.map(p=>p.number),[10,11]);
  assert.equal(rosterPage(value,Date.parse(started)+15000).page,0);
  assert.equal(rosterPage(value,Date.parse(started)+50000).page,1);
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
  assert.equal(rosterPage(value, clock.current()).page, 0);
  clock.accept({...value, matchId:id(99),serverNow:'2026-01-01T00:00:01Z'});
  assert.equal(rosterPage(value, clock.current()).page, 0);
});

test('early confirmation waits for full roster pass then loops selected seven and four cards', () => {
  const value=fixture();
  value.phase={kind:'ROSTER',startedAt:started,pointId:id(50),lineupRevision:1,
    lineupStartedAt:'2026-01-01T00:00:15Z',anchorKind:'CONFIRMED'};
  value.teams.forEach((team,t)=>team.places=t ? Array.from({length:4},(_,p)=>({participantId:id(300+p),name:`Selected ${p}`,number:p,photoUrl:null})) : team.roster.slice(0,7));
  assert.equal(rosterPage(value,Date.parse(started)+14999).phase.kind,'ROSTER');
  const page=rosterPage(value,Date.parse(started)+20000);
  assert.equal(page.phase.kind,'LINEUP');
  assert.deepEqual(page.teams.map(t=>t.roster.length),[2,4]);
  assert.equal(rosterPage(value,Date.parse(started)+25000).page,0);
  const reconnect={...value,phase:{...value.phase,kind:'LINEUP',startedAt:value.phase.lineupStartedAt}};
  assert.deepEqual(rosterPage(reconnect,Date.parse(started)+20000).teams,page.teams);
});
test('late confirmation and empty selections preserve honest lineup state', () => {
  const value=fixture();
  value.phase={kind:'LINEUP',startedAt:'2026-01-01T00:00:30Z',pointId:id(50),lineupRevision:1,
    lineupStartedAt:'2026-01-01T00:00:30Z',anchorKind:'LEGACY_POINT_OPEN'};
  value.teams.forEach(team=>team.places=[]);
  const page=rosterPage(value,Date.parse(started)+90000);
  assert.equal(page.phase.kind,'LINEUP');assert.equal(page.page,0);
  assert.deepEqual(page.teams.map(t=>t.roster),[[],[]]);
});

test('old source payload is discarded across phase and revision changes without resetting time', () => {
  let now=0;const clock=new RosterClock(()=>now), value=fixture();clock.accept(value);
  now=6000;const current=clock.current();
  assert.equal(clock.accept({...value,presentationRevision:'unrelated',phase:{kind:'LINEUP',startedAt:value.serverNow}}),true);
  assert.equal(clock.current(),current);
  assert.equal(clock.accept({...value,serverNow:started}),false);
  assert.equal(clock.current(),current);
});
test('lineup validates phase metadata and anonymous temporary tiles', () => {
  const value=fixture();value.phase={kind:'LINEUP',startedAt:started,lineupStartedAt:started,pointId:id(50),lineupRevision:1,anchorKind:'CONFIRMED'};
  value.teams.forEach(t=>t.places=[]);
  value.teams[0].places=[{participantId:id(500),name:'Игрок неизвестен',number:null,photoUrl:null,temporary:true}];
  assert.equal(validateInfographics(value,id(1)).teams[0].places[0].temporary,true);
  value.teams[0].places[0].number=7;assert.throws(()=>validateInfographics(value,id(1)));
  value.teams[0].places[0].number=null;value.phase.anchorKind='fabricated';assert.throws(()=>validateInfographics(value,id(1)));
});

test('scheduled lineup uses its own captured interval, independent of roster and latest settings', () => {
  const value=fixture();
  value.phase={kind:'ROSTER',startedAt:started,pageSeconds:2,lineupPageSeconds:7,
    pointId:id(50),lineupRevision:1,lineupStartedAt:'2026-01-01T00:00:06Z',anchorKind:'CONFIRMED'};
  value.settings={rosterPageSeconds:15,pointPageSeconds:3,leaderPageSeconds:8};
  value.teams.forEach(team=>team.places=team.roster);
  assert.equal(rosterPage(value,Date.parse(started)+12000).page,0);
  assert.equal(rosterPage(value,Date.parse(started)+13000).page,1);
  assert.equal(rosterPage(value,Date.parse(started)+5000).page,2);
});

test('finite leader pages run three attack then two defense slots and restore the current base', async () => {
  const {infographicsPage} = await import('../../main/resources/static/obs/infographics-model.mjs');
  const value = fixture();
  const player = n => ({participantId:id(n),name:`Player ${n}`,number:n,ratingTenths:15,
    components:{ordinaryAccuratePasses:0,ratedGoals:1,assists:0,blocks:1,blocksAsMarker:0,blocksAsFieldPlayer:0,interceptions:0,callahans:0}});
  value.leaderPresentation = {generation:id(500),startedAt:started,expiresAt:'2026-01-01T00:00:15Z',pageSeconds:3,
    teams:value.teams.map((team,i)=>({teamId:team.teamId,state:'READY',attack:(i?[11]:[1,2,3]).map(player),defense:(i?[12,13]:[4]).map(player)}))};
  const at = sec => infographicsPage(validateInfographics(value,id(1)),Date.parse(started)+sec*1000);
  assert.deepEqual([0,3,6,9,12].map(sec=>{
    const p=at(sec);return [p.category,p.teams.map(t=>t.leader?.number)];
  }), [['attack',[1,11]],['attack',[2,11]],['attack',[3,11]],['defense',[4,12]],['defense',[4,13]]]);
  // Reconnected payload has the same finite command. Current E setting and current base have changed.
  value.settings.leaderPageSeconds=15;
  value.phase={kind:'LINEUP',startedAt:started,lineupStartedAt:started,lineupRevision:1,pointId:id(10),anchorKind:'CONFIRMED'};
  assert.equal(at(12).category,'defense');
  assert.equal(at(15).phase.kind,'LINEUP');
  value.leaderPresentation.teams[1].attack=[];
  assert.deepEqual(at(3).teams.map(t=>t.leader?.number),[2,undefined]);
  value.leaderPresentation.teams[0].attack=[];
  value.leaderPresentation.expiresAt='2026-01-01T00:00:06Z';
  assert.equal(at(0).category,'defense');
  value.leaderPresentation.expiresAt='2026-01-01T00:00:10Z';
  assert.throws(()=>at(0));
  value.leaderPresentation=null;
  assert.equal(at(0).phase.kind,'LINEUP');
});
