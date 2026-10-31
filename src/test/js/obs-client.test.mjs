import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Reconciler,watchMatch} from '../../main/resources/static/obs/client.mjs';

test('serializes invalidations and final reconciliation; retries failed final read',async()=>{
 let running=0,peak=0,calls=0,fail=false,release;
 const gate=new Promise(r=>release=r), values=[];
 const sync=new Reconciler(async()=>{ running++; peak=Math.max(peak,running); calls++; if(calls===1) await gate;
  running--; if(fail){fail=false;throw Error('offline');} return calls; },value=>values.push(value),()=>{},5);
 const first=sync.request(); sync.request(); sync.request(); release(); await first;
 assert.equal(peak,1); assert.deepEqual(values,[1,2]);
 fail=true; await sync.finish(); assert.deepEqual(values,[1,2,4]); sync.close();
});
test('requests arriving during failed fetch are retained and close cancels retries',async()=>{
 let calls=0; const values=[];
 const sync=new Reconciler(async()=>{calls++;if(calls===1)throw Error('offline');return calls;},v=>values.push(v),()=>{},5);
 await sync.request(); assert.deepEqual(values,[2]);sync.close();await sync.request();assert.equal(calls,2);
});

import {Feed,defaults} from '../../main/resources/static/obs/feed.mjs';
const goal={id:'goal',type:'GOAL',sequenceNumber:1,fromParticipantId:'a',toParticipantId:'b'};
const settle=()=>new Promise(setImmediate);
function watcher(t,hasVisibleRows,initiallyFinished=false) {
 t.mock.timers.enable({apis:['Date','setTimeout','setInterval'],now:0});
 const oldWorker=globalThis.SharedWorker,oldFetch=globalThis.fetch;
 const state={snapshot:{events:[],logRevision:0},reads:0,fail:false};
 class Port {
  constructor(){this.closed=false;state.source=this;}
  start(){}
  postMessage(message){if(message.type==='unsubscribe')this.closed=true;}
  emit(name){if(!this.closed)this.onmessage?.({data:{type:'event',name,data:{}}});}
  close(){this.closed=true;}
 }
 globalThis.SharedWorker=class {constructor(){this.port=new Port();}};
 globalThis.fetch=async()=>{
  state.reads++;if(state.fail)throw Error('offline');
  return {ok:true,json:async()=>state.snapshot};
 };
 state.feed=new Feed(defaults,()=>Date.now());
 state.stop=watchMatch('match',value=>state.feed.reconcile(value),()=>{},hasVisibleRows??(()=>state.feed.rows().length>0),initiallyFinished);
 t.after(()=>{state.stop();globalThis.SharedWorker=oldWorker;globalThis.fetch=oldFetch;});
 return state;
}
test('terminal window updates and deletes visible rows without replay or extending TTL',async t=>{
 const s=watcher(t);s.source.emit('connected');await settle();
 s.snapshot={events:[goal],logRevision:1};s.source.emit('event-log-changed');await settle();
 assert.equal(s.feed.rows().length,1);
 const shownAt=s.feed.rows()[0].shownAt;
 s.source.emit('match-finished');await settle();assert.equal(s.source.closed,true);
 s.snapshot={events:[{...goal,toParticipantId:'corrected'}],logRevision:2};
 t.mock.timers.tick(1000);await settle();
 assert.equal(s.feed.rows()[0].event.toParticipantId,'corrected');
 assert.equal(s.feed.rows()[0].shownAt,shownAt);
 s.snapshot={events:[],logRevision:3};t.mock.timers.tick(1000);await settle();
 assert.equal(s.feed.rows().length,0);
 t.mock.timers.tick(1000);await settle();const reads=s.reads;
 s.snapshot={events:[goal],logRevision:4};t.mock.timers.tick(10000);await settle();
 assert.equal(s.reads,reads);assert.equal(s.feed.rows().length,0);
});
test('empty feed still retries final goal read then stops polling when its TTL expires',async t=>{
 const s=watcher(t);s.source.emit('connected');await settle();
 s.fail=true;s.source.emit('match-finished');await settle();
 assert.equal(s.feed.rows().length,0);
 s.snapshot={events:[goal],logRevision:1};s.fail=false;
 t.mock.timers.tick(1000);await settle();assert.equal(s.feed.rows().length,1);
 t.mock.timers.tick(1000);await settle();assert.equal(s.reads,4,'final goal opens correction polling');
 t.mock.timers.tick(7000);await settle();assert.equal(s.feed.rows().length,0);
 const reads=s.reads;t.mock.timers.tick(10000);await settle();assert.equal(s.reads,reads);
});
test('terminal polling and failed retries are bounded to sixty seconds and disposed on page close',async t=>{
 const s=watcher(t,()=>true);s.source.emit('connected');await settle();
 s.source.emit('match-finished');await settle();
 s.fail=true;t.mock.timers.tick(1000);await settle();assert.equal(s.reads,3,'terminal polling must have started');
 t.mock.timers.tick(59000);await settle();const reads=s.reads;
 t.mock.timers.tick(120000);await settle();assert.equal(s.reads,reads);
 s.stop();t.mock.timers.tick(120000);await settle();assert.equal(s.reads,reads);
});
test('disposing during final read failure cancels retries without starting a terminal timer',async t=>{
 const s=watcher(t,()=>true);s.source.emit('connected');await settle();
 s.fail=true;s.source.emit('match-finished');await settle();s.stop();await settle();
 const reads=s.reads;t.mock.timers.tick(120000);await settle();assert.equal(s.reads,reads);
});
test('successful empty final snapshot starts no polling window',async t=>{
 const s=watcher(t);s.source.emit('connected');await settle();
 s.source.emit('match-finished');await settle();assert.equal(s.reads,2);
 t.mock.timers.tick(120000);await settle();assert.equal(s.reads,2);
});

test('source opened for an already finished match keeps history quiet and terminates match subscription',async t=>{
 const s=watcher(t,undefined,true);s.snapshot={events:[goal],logRevision:1};
 s.source.emit('connected');await settle();
 assert.equal(s.source.closed,true);assert.equal(s.feed.rows().length,0);assert.equal(s.reads,1);
 t.mock.timers.tick(120000);await settle();assert.equal(s.reads,1);
});
