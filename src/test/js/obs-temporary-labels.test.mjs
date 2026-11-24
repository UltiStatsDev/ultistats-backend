import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Feed,resolveRow} from '../../main/resources/static/obs/feed.mjs';
import {eventLogMatch,validateEventLog} from '../../main/resources/static/obs/protected-event-log-model.mjs';
import {ids,instant,temporarySnapshot} from './fixtures/obs-temporary-labels.mjs';

test('canonical temporary actors label both original and separate cancellation at maxRows1/TTL45 without mutation',()=>{
  const snapshot=temporarySnapshot(), before=JSON.stringify(snapshot);let now=0;
  validateEventLog(snapshot,ids.match);
  const feed=new Feed(snapshot.config,()=>now,{protected:true});
  feed.reconcile({...snapshot,events:[]});feed.reconcile({...snapshot,logRevision:2});
  const original=feed.rows()[0];now=3000;
  const cancelled={...snapshot,serverNow:instant(now),logRevision:3,events:[],retractions:[{event:snapshot.events[0],cancelledAt:instant(now)}]};
  const cancelledBefore=JSON.stringify(cancelled);feed.reconcile(cancelled);
  assert.deepEqual(feed.rows().map(row=>row.kind),['cancellation','original']);
  assert.notEqual(feed.rows()[0].key,original.key);assert.equal(feed.rows()[1].deadline,original.deadline);
  const match=eventLogMatch(cancelled);
  for(const entry of feed.rows()) {
    assert.equal(entry.event.fromParticipantId,ids.first);assert.equal(entry.event.toParticipantId,ids.second);
    const row=resolveRow(entry.event,match);
    assert.deepEqual([row.left.label,row.right.label],['Запасной №1','Запасной №2']);
    assert.deepEqual([row.left.teamId,row.right.teamId],[ids.team,ids.team]);
  }
  assert.deepEqual(match.teams[0].participants.filter(p=>p.kind==='TEMPORARY').map(p=>[p.participantId,p.number]),[[ids.first,1],[ids.second,2]]);
  assert.equal(JSON.stringify(snapshot),before);assert.equal(JSON.stringify(cancelled),cancelledBefore);
  now=45000;assert.deepEqual(feed.rows().map(row=>row.kind),['cancellation']);
  now=48000;assert.equal(feed.rows().length,0);
});
test('only a canonical same-team PLAYER may resolve a temporary actor to a known name',()=>{
  const snapshot=temporarySnapshot(), event=snapshot.events[0];
  snapshot.temporarySlots[0].resolvedParticipantId=ids.known;
  assert.equal(resolveRow(event,eventLogMatch(snapshot)).left.label,'7 Иванов И.');
  for(const reference of [ids.opposing,ids.legacy,ids.missing]) {
    snapshot.temporarySlots[0].resolvedParticipantId=reference;
    const actor=resolveRow(event,eventLogMatch(snapshot)).left;
    assert.deepEqual(actor,{label:'Запасной №1',teamId:ids.team});
  }
  snapshot.temporarySlots[0].resolvedParticipantId=ids.known;
  snapshot.temporarySlots[1].resolvedParticipantId=ids.first;
  assert.equal(resolveRow(event,eventLogMatch(snapshot)).right.label,'Запасной №2','slot aliases are not canonical PLAYER references');
});
test('legacy UNKNOWN and absent or unnumbered temporary metadata retain generic fallback',()=>{
  const snapshot=temporarySnapshot(), event=snapshot.events[0];
  assert.equal(resolveRow({...event,fromParticipantId:ids.known},eventLogMatch(snapshot)).left.label,'7 Иванов И.');
  assert.equal(resolveRow({...event,fromParticipantId:ids.legacy},eventLogMatch(snapshot)).left.label,'Неизвестный игрок');
  snapshot.temporarySlots=[];
  assert.equal(resolveRow(event,eventLogMatch(snapshot)).left.label,'Неизвестный игрок');
  const match={teams:[{teamId:ids.team,participants:[{kind:'TEMPORARY',participantId:ids.first}]}]};
  assert.equal(resolveRow(event,match).left.label,'Неизвестный игрок');
});
