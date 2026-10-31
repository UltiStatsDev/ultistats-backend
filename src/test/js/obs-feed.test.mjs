import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Feed, resolveRow, defaults} from '../../main/resources/static/obs/feed.mjs';
const event = (n, type='GOAL') => ({id:`e${n}`,sequenceNumber:n,type,fromParticipantId:'a',toParticipantId:'b'});
const snap = (events, logRevision=1) => ({events,logRevision});

test('quiet first load; latest bounded canonical batch; no duplicate replay',()=>{
 const feed=new Feed(defaults,()=>0);
 feed.reconcile(snap([event(1)])); assert.equal(feed.rows().length,0);
 feed.reconcile(snap(Array.from({length:100},(_,i)=>event(i+1)),2));
 assert.deepEqual(feed.rows().map(r=>r.event.sequenceNumber),[100,99,98,97,96]);
 feed.reconcile(snap([event(1),event(100)],3)); assert.deepEqual(feed.rows().map(r=>r.event.sequenceNumber),[100]);
 feed.reconcile(snap([event(1),event(100)],3)); assert.equal(feed.rows().length,1);
});
test('visible edits preserve expiry, deletes remove, expired and previously removed do not resurrect',()=>{
 let now=0; const feed=new Feed(defaults,()=>now); feed.reconcile(snap([]));
 feed.reconcile(snap([event(1)],2)); now=7000;
 feed.reconcile(snap([{...event(1),toParticipantId:'c'}],3)); assert.equal(feed.rows()[0].event.toParticipantId,'c');
 now=8000; assert.equal(feed.rows().length,0);
 feed.reconcile(snap([{...event(1),toParticipantId:'d'}],4)); assert.equal(feed.rows().length,0);
 feed.reconcile(snap([],5)); feed.reconcile(snap([event(1)],6)); assert.equal(feed.rows().length,0);
});
test('disabled events do not surface later; max and TTL changes apply; stale revisions ignored',()=>{
 let now=0; const feed=new Feed({...defaults,categories:[]},()=>now); feed.reconcile(snap([]));
 feed.reconcile(snap([event(1)],2)); feed.configure(defaults);
 feed.reconcile(snap([event(1)],3)); assert.equal(feed.rows().length,0);
 feed.reconcile(snap([event(1),event(2)],4));
 feed.reconcile(snap([],2)); assert.equal(feed.rows().length,1);
 now=2000; feed.configure({...defaults,ttlSeconds:1}); assert.equal(feed.rows().length,0);
});
test('all event directions and participant labels including unknown and zero numbers',()=>{
 const match={teams:[{teamId:'t1',participants:[{participantId:'a',kind:'PLAYER',number:22,lastName:'Сартаков',firstName:'Михаил'}]},
 {teamId:'t2',participants:[{participantId:'b',kind:'PLAYER',number:44,lastName:'Братчиков',firstName:'Денис'}]}]};
 assert.equal(resolveRow(event(1),match).left.label,'22 Сартаков М.');
 for (const type of ['BLOCK','BLOCK_MARKER','BLOCK_FIELD','INTERCEPTION','CALLAHAN']) {
   const row=resolveRow(event(1,type),match); assert.equal(row.left.label,'44 Братчиков Д.'); assert.equal(row.right.teamId,'t1');
 }
 assert.equal(resolveRow(event(1,'PASS'),match),null);
 match.teams[0].participants[0]={participantId:'a',kind:'UNKNOWN'};
 assert.match(resolveRow(event(1),match).left.label,/Неизвестный/);
 match.teams[0].participants[0]={participantId:'a',kind:'PLAYER',number:0,lastName:'Один'};
 assert.equal(resolveRow(event(1),match).left.label,'0 Один');
 assert.doesNotMatch(resolveRow({...event(1),fromParticipantId:'missing'},match).left.label,/null|undefined/);
});
test('configured small bounds, canonical unsorted batch and category filtering',()=>{
 let now=0;const f=new Feed({...defaults,maxRows:2,categories:['CALLAHAN','BLOCK']},()=>now);
 f.reconcile(snap([]));f.reconcile(snap([event(6,'BLOCK_FIELD'),event(2,'CALLAHAN'),event(4,'INTERCEPTION'),event(3,'BLOCK_MARKER'),event(5,'GOAL')],2));
 assert.deepEqual(f.rows().map(r=>r.event.sequenceNumber),[6,3]);
 f.configure({...defaults,maxRows:1});assert.equal(f.rows().length,1);
 f.reconcile(snap([event(6,'BLOCK_FIELD'),event(2,'CALLAHAN'),event(3,'BLOCK_MARKER')],3));
 assert.equal(f.rows().length,1);
 now=9000;assert.equal(f.rows().length,0);
});
