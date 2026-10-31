import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const script=()=>readFileSync(new URL('../../main/resources/static/obs/shared-worker.js',import.meta.url),'utf8');
function worker(failConstruct=0) {
 const sources=[],timers=new Map();let now=0,timerId=0;
 class Source {
  constructor(url){if(failConstruct-->0)throw Error('constructor unavailable');this.url=url;this.readyState=0;this.handlers={};sources.push(this);}
  addEventListener(name,handler){this.handlers[name]=handler;}
  emit(name,data={}){if(name==='connected')this.readyState=1;this.handlers[name]?.({data:JSON.stringify(data)});}
  error(state){this.readyState=state;this.onerror?.();}
  close(){this.closed=true;this.readyState=2;}
 }
 const scope={};
 vm.runInNewContext(script(),{self:scope,EventSource:Source,Date:{now:()=>now},
  setInterval:(fn,ms)=>{timers.set(++timerId,{fn,ms,at:now+ms,interval:true});return timerId;},clearInterval:id=>timers.delete(id),
  setTimeout:(fn,ms)=>{timers.set(++timerId,{fn,ms,at:now+ms});return timerId;},clearTimeout:id=>timers.delete(id)});
 const connect=(matchId=null,pong=true)=>{
  const p={out:[],closed:false,start(){},close(){this.closed=true;},
   postMessage(message){this.out.push(JSON.parse(JSON.stringify(message)));if(message.type==='ping'&&pong)this.send({type:'pong'});},
   send(data){this.onmessage?.({data});}};
  scope.onconnect({ports:[p]});p.send({type:'subscribe',matchId});return p;
 };
 return {sources,timers,connect,retries:()=>[...timers.values()].filter(t=>!t.interval).map(t=>t.ms),tick:ms=>{now+=ms;for(const [id,t]of [...timers])if(t.at<=now){if(t.interval)t.at=now+t.ms;else timers.delete(id);t.fn();}}};
}
const events=p=>p.out.filter(x=>x.type==='event').map(x=>x.name);
test('six match ports plus admin/preview share one SSE, with filtered notifications and reconnect bootstrap',()=>{
 const w=worker(),ports=Array.from({length:6},(_,i)=>w.connect('match-'+i)),admin=w.connect();
 assert.equal(w.sources.length,1,'all consumers must share a single notification connection');
 assert.equal(w.sources[0].url,'/api/v1/obs/stream');
 const source=w.sources[0];source.emit('connected');
 for(const p of [...ports,admin])assert.deepEqual(events(p),['connected']);
 const preview=w.connect('match-0');assert.deepEqual(events(preview),['connected'],'new port bootstraps on already-open source');
 source.emit('event-log-changed',{matchId:'match-0',operation:'UPDATED'});
 assert.deepEqual(events(ports[0]),['connected','event-log-changed']);
 assert.deepEqual(events(preview),['connected','event-log-changed']);
 for(const p of [...ports.slice(1),admin])assert.deepEqual(events(p),['connected']);
 source.emit('match-finished',{matchId:'match-0'});source.emit('config-changed');
 assert.equal(events(admin).at(-1),'config-changed');
 assert.equal(events(ports[1]).includes('match-finished'),false);
 source.emit('connected');for(const p of [...ports,admin,preview])assert.equal(events(p).at(-1),'connected');
 assert.equal(w.sources.length,1);
});
test('unregister, silent port expiry and reconnect do not grow listeners or keep unused SSE alive',()=>{
 const w=worker(),first=w.connect('one'),second=w.connect('two',false);
 const source=w.sources[0];assert.ok(source,'registered ports need a stream');source.emit('connected');
 first.send({type:'unsubscribe'});assert.equal(source.closed,undefined,'other port retains shared source');
 w.tick(45001);assert.equal(source.closed,true);assert.equal(w.timers.size,0);
 assert.equal(second.closed,true);assert.equal(second.out.at(-1).type,'expired');
 const reopened=w.connect('two');assert.equal(w.sources.length,2);w.sources[1].emit('connected');
 assert.deepEqual(events(reopened),['connected']);
 reopened.send({type:'unsubscribe'});assert.equal(w.sources[1].closed,true);assert.equal(w.timers.size,0);
});
test('scoreboard profile and lifecycle invalidations are routed to matching match only through existing single stream',()=>{
 const w=worker(),one=w.connect('one'),two=w.connect('two'),eventLog=w.connect('one'),admin=w.connect();
 const source=w.sources[0];source.emit('connected');
 source.emit('match-changed',{matchId:'one'});source.emit('scoreboard-profile-changed',{matchId:'two'});
 assert.deepEqual(events(one),['connected','match-changed']);
 assert.deepEqual(events(eventLog),['connected','match-changed']);
 assert.deepEqual(events(two),['connected','scoreboard-profile-changed']);
 assert.deepEqual(events(admin),['connected']);assert.equal(w.sources.length,1);
});

test('terminal CLOSED stream is recreated once while CONNECTING remains browser-managed and stale callbacks cannot damage recovery',()=>{
 const w=worker(),one=w.connect('one'),two=w.connect('two'),admin=w.connect();const old=w.sources[0];old.emit('connected');
 old.error(0);assert.equal(w.sources.length,1);assert.deepEqual(w.retries(),[],'CONNECTING must not add a second reconnect loop');
 const staleError=old.onerror;old.error(2);assert.deepEqual(w.retries(),[1000]);
 for(let i=0;i<8;i++)w.connect('one');assert.equal(w.sources.length,1,'new subscribers must not bypass shared backoff');
 w.tick(999);assert.equal(w.sources.length,1);w.tick(1);assert.equal(w.sources.length,2);
 const fresh=w.sources[1];assert.equal(old.closed,true);fresh.emit('connected');
 staleError();old.emit('config-changed');assert.deepEqual(w.retries(),[]);assert.equal(fresh.closed,undefined);
 fresh.emit('event-log-changed',{matchId:'one'});fresh.emit('scoreboard-profile-changed',{matchId:'two'});fresh.emit('config-changed');
 assert.deepEqual(events(one),['connected','connected','event-log-changed','config-changed']);
 assert.deepEqual(events(two),['connected','connected','scoreboard-profile-changed','config-changed']);
 assert.deepEqual(events(admin),['connected','connected','config-changed']);
 assert.equal(w.sources.filter(s=>!s.closed).length,1);
});
test('initial terminal failures use bounded exponential retry reset by real connected and final unsubscribe cancels it',()=>{
 const w=worker(),port=w.connect('one');
 for(const delay of [1000,2000,4000,8000,15000,15000]){
  const source=w.sources.at(-1),before=w.sources.length;source.error(2);source.error(2);
  assert.deepEqual(w.retries(),[delay]);w.tick(delay-1);assert.equal(w.sources.length,before);w.tick(1);assert.equal(w.sources.length,before+1);
 }
 w.sources.at(-1).emit('connected');w.sources.at(-1).error(2);assert.deepEqual(w.retries(),[1000]);
 port.send({type:'unsubscribe'});assert.equal(w.timers.size,0);const count=w.sources.length;w.tick(120000);assert.equal(w.sources.length,count);
 const reopened=w.connect('two');assert.equal(w.sources.length,count+1);reopened.send({type:'unsubscribe'});assert.equal(w.timers.size,0);
});
test('expired last silent subscriber cancels terminal retry without resurrecting an unused connection',()=>{
 const w=worker(),port=w.connect('one',false);w.sources[0].error(2);w.tick(45001);
 assert.equal(port.closed,true);assert.equal(w.timers.size,0);const count=w.sources.length;w.tick(120000);assert.equal(w.sources.length,count);
});

test('synchronous EventSource construction failure is retried without uncaught error or retry after final unsubscribe',()=>{
 const w=worker(1),port=w.connect('one');assert.equal(w.sources.length,0);assert.deepEqual(w.retries(),[1000]);
 w.tick(1000);assert.equal(w.sources.length,1);w.sources[0].emit('connected');assert.deepEqual(events(port),['connected']);
 port.send({type:'unsubscribe'});assert.equal(w.timers.size,0);w.tick(60000);assert.equal(w.sources.length,1);
});
