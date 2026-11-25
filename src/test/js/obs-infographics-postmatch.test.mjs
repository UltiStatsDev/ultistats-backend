import assert from 'node:assert/strict';
import test from 'node:test';
import { validateInfographics, rosterPage } from '../../main/resources/static/obs/infographics-model.mjs';
import { renderPostmatch, validatePostmatch } from '../../main/resources/static/obs/infographics-postmatch.mjs';

const teams = [{teamId:'left', name:'Север'}, {teamId:'right', name:'Юг'}];
const raw = (left, right) => ({
  statsAsOfEventAt:'2026-01-02T00:01:00Z',
  teams:[left, right].map((possession, i) => ({
    teamId:teams[i].teamId, score:0, accuracy:i === 0 ? 0 : null, possession, possessionTimeMs:100,
    metrics:{accuratePasses:0, inaccuratePasses:i+1, blocks:9-i*3, interceptions:0, callahans:null, breaks:0},
  })),
});
const render = (left, right) => {
  const node = (tag, text='', className='') => ({
    tag, text, className, attributes:{}, children:[], style:{},
    setAttribute(key, value) { this.attributes[key] = value; },
    append(...children) { this.children.push(...children); },
  });
  return renderPostmatch(node, teams, validatePostmatch(raw(left, right), teams));
};
const all = (element, cls) => element.children.flatMap(child =>
  [...(child.className === cls ? [child] : []), ...all(child, cls)]);
const ringValues = element => all(element, 'postmatch-ring-value').map(ring => ring.text);

test('renders four rings with ordinary possession labels and unrounded fill', () => {
  const third = render(1/3, 2/3);
  assert.deepEqual(ringValues(third), ['0.0 %','33.3 %','—','66.7 %']);
  assert.equal(all(third, 'postmatch-ring')[1].style.background, 'conic-gradient(var(--postmatch-color) 33.33333333333333%, #344055 0)');
  assert.deepEqual(ringValues(render(0.6, 0.4)).filter((_,i)=>i%2), ['60.0 %','40.0 %']);
  assert.deepEqual(ringValues(render(0, 1)).filter((_,i)=>i%2), ['0.0 %','100.0 %']);
});

test('shows unavailable possession, comparison counts and empty zero fills', () => {
  const panel = render(null, null);
  assert.deepEqual(ringValues(panel), ['0.0 %','—','—','—']);
  assert.equal(all(panel, 'postmatch-no-possession')[0].text, 'Нет данных о владении.');
  const rows = all(panel, 'postmatch-row');
  assert.equal(rows.length, 6);
  const widths = row => all(row,'postmatch-fill').map(fill=>parseFloat(fill.style.width));
  assert.deepEqual(widths(rows[0]),[0,0]);
  assert.deepEqual(widths(rows[1]),[50,100]);
  assert.equal(widths(rows[2])[0],100);
  assert.ok(Math.abs(widths(rows[2])[1] - 200/3) < 1e-12);
  assert.deepEqual(all(rows[4], 'postmatch-count').map(count => count.text), ['—','—']);
});

test('rejects foreign postmatch team IDs and makes nonfinite or negative values unavailable', () => {
  const invalid = raw(1.01, -0.01);
  invalid.teams[0].metrics.breaks = -1;
  invalid.teams[1].metrics.interceptions = Infinity;
  const clean = validatePostmatch(invalid, teams);
  assert.deepEqual(clean.teams.map(team=>team.possession), [null,null]);
  assert.equal(clean.teams[0].metrics.breaks, null);
  assert.equal(clean.teams[1].metrics.interceptions, null);
  invalid.teams[0].teamId = 'stale';
  assert.throws(() => validatePostmatch(invalid, teams), /инфографики/);
});

test('accepts the finished postmatch for the exact snapshot and rejects live or crossed payloads', () => {
  const uuid = n => `00000000-0000-0000-0000-00000000000${n}`;
  const sides = teams.map((team,i) => ({...team, teamId:uuid(i+1), roster:[]}));
  const panel = raw(0.6,0.4);
  panel.teams.forEach((team,i) => { team.teamId = sides[i].teamId; });
  const snapshot = {
    matchId:uuid(3), serverNow:'2026-01-02T00:12:00Z', presentationRevision:'revision',
    status:'FINISHED', phase:{kind:'POSTMATCH',startedAt:null}, teams:sides,
    settings:{rosterPageSeconds:5,pointPageSeconds:5,leaderPageSeconds:5},
    postmatch:panel,
  };
  const accepted = validateInfographics(snapshot,snapshot.matchId);
  assert.deepEqual(accepted.postmatch,panel);
  assert.equal(rosterPage(accepted,Date.parse(snapshot.serverNow)),null);
  assert.throws(() => validateInfographics({...snapshot,status:'IN_PROGRESS'},snapshot.matchId), /инфографики/);
  assert.throws(() => validateInfographics({...snapshot,postmatch:null},snapshot.matchId), /инфографики/);
  assert.throws(() => validateInfographics({...snapshot,phase:{kind:'HIDDEN',startedAt:null}},snapshot.matchId), /инфографики/);
  assert.throws(() => validateInfographics(snapshot,uuid(4)), /инфографики/);
});
