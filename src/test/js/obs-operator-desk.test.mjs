import assert from 'node:assert/strict';
import {test} from 'node:test';

const id = 'abcdefab-cdef-abcd-efab-cdefabcdefab';
const generation = '12345678-1234-1234-1234-123456789abc';
const now = Date.parse('2026-11-25T00:00:00Z');
const receipt = {kind:'STATS', origin:'MANUAL', matchId:id, generation, expiresAt:new Date(now + 2050).toISOString()};
const phase = {kind:'ROSTER', origin:'AUTO', phaseToken:'current-phase'};
const control = {binding:'EXACT', matchId:id, expectedGeneration:null, expectedInputRevision:'revision',
  expectedSettingsRevision:2, availability:{STATS:true, POINT:false}, activePresentation:receipt};

test('timed display uses the server-clock deadline and selects only the live generation', async () => {
  const {activeGraphic, commandFor} = await import('../../main/resources/static/obs/operator-desk.mjs');
  const active = activeGraphic(control, now);
  assert.equal(active.seconds,3);
  assert.equal(activeGraphic(control,now+2050),null);
  assert.deepEqual(commandFor('hide',control,active,'op'),{
    method:'DELETE',path:`/api/v1/obs/infographics/presentations/${generation}`,
  });
  assert.equal(commandFor('hide',control,null,'op'),null);
});

test('scope and revisions stay captured for show and automatic phase hide', async () => {
  const {activeGraphic, commandFor} = await import('../../main/resources/static/obs/operator-desk.mjs');
  const auto = {...control, binding:'AUTO', activePresentation:null, activePhase:phase};
  const active = activeGraphic(auto,now);
  assert.deepEqual(active,{...phase,seconds:null});
  assert.deepEqual(commandFor('hide',auto,active,'op'),{
    method:'POST',path:'/api/v1/obs/infographics/presentations/hide-phase',
    body:{binding:'AUTO',expectedMatchId:id,expectedGeneration:null,operationId:'op',phaseToken:'current-phase'},
  });
  assert.equal(commandFor('POINT',control,active,'op'),null);
  assert.deepEqual(commandFor('STATS',control,active,'op').body,{
    binding:'EXACT',kind:'STATS',expectedMatchId:id,expectedGeneration:null,
    expectedInputRevision:'revision',expectedSettingsRevision:2,operationId:'op',
  });
});
