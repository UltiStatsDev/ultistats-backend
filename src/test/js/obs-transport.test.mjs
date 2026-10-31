import {test} from 'node:test';
import assert from 'node:assert/strict';
import {SharedSource} from '../../main/resources/static/obs/transport.mjs';
function setup(t) {
 t.mock.timers.enable({apis:['Date','setTimeout','setInterval'],now:0});
 const oldWorker=globalThis.SharedWorker,oldSource=globalThis.EventSource,workers=[];
 globalThis.EventSource=class{constructor(){throw Error('per-source SSE fallback is forbidden');}};
 globalThis.SharedWorker=class {
  constructor(url,options){this.url=url;this.options=options;workers.push(this);
   this.port={sent:[],start(){},postMessage(message){this.sent.push(message);},close(){this.closed=true;},emit(data){this.onmessage?.({data});}};
  }
 };
 t.after(()=>{globalThis.SharedWorker=oldWorker;globalThis.EventSource=oldSource;});return workers;
}
test('consumer registers filtered port, handles notifications/pings and disposes without touching other ports',t=>{
 const workers=setup(t),source=new SharedSource('match');
 t.after(()=>source.close());assert.equal(workers.length,1);
 const w=workers[0];assert.equal(w.url,'/obs/shared-worker.js');assert.equal(w.options.name,'ultistats-obs');
 assert.deepEqual(w.port.sent,[{type:'subscribe',matchId:'match'}]);
 let connects=0;source.addEventListener('connected',()=>connects++);
 w.port.emit({type:'event',name:'connected',data:{scope:'service'}});assert.equal(connects,1);
 w.port.emit({type:'ping'});assert.deepEqual(w.port.sent.at(-1),{type:'pong'});
 source.close();assert.equal(w.port.closed,true);assert.deepEqual(w.port.sent.at(-1),{type:'unsubscribe'});
 w.port.emit({type:'event',name:'connected'});assert.equal(connects,1);
});
test('worker crash or expired lease reconnects with backoff and bootstrap; disposal cancels recovery',t=>{
 const workers=setup(t),source=new SharedSource();t.after(()=>source.close());assert.equal(workers.length,1);
 let errors=0,connected=0;source.onerror=()=>errors++;source.addEventListener('connected',()=>connected++);
 workers[0].onerror();assert.equal(errors,1);assert.equal(workers[0].port.closed,true);
 t.mock.timers.tick(1000);assert.equal(workers.length,2);
 workers[1].port.emit({type:'event',name:'connected',data:{}});assert.equal(connected,1);
 workers[1].port.emit({type:'expired'});t.mock.timers.tick(1000);assert.equal(workers.length,3);
 workers[2].onerror();source.close();t.mock.timers.tick(60000);assert.equal(workers.length,3);
});
test('unsupported SharedWorker reports failure and never opens per-source EventSource',t=>{
 const workers=setup(t);globalThis.SharedWorker=undefined;
 const source=new SharedSource();t.after(()=>source.close());let errors=0;source.onerror=()=>errors++;
 t.mock.timers.tick(1000);assert.equal(errors,1);assert.equal(workers.length,0);
 source.close();t.mock.timers.tick(60000);assert.equal(errors,1);
});

test('silent worker termination is detected and rejoined without adding EventSources per page',t=>{
 const workers=setup(t),source=new SharedSource('match');t.after(()=>source.close());
 assert.equal(workers.length,1);
 t.mock.timers.tick(45001);t.mock.timers.tick(1000);
 assert.equal(workers.length,2,'missing worker heartbeat must recover the port');
 source.close();t.mock.timers.tick(120000);assert.equal(workers.length,2);
});
