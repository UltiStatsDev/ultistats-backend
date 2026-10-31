import {test} from 'node:test';
import assert from 'node:assert/strict';
import {elapsedLabel,scoreboardTeams,ClockAnchor,defaultProfile} from '../../main/resources/static/obs/scoreboard-model.mjs';
import {watchScoreboard} from '../../main/resources/static/obs/scoreboard-client.mjs';
const sample=()=>({matchId:'a',status:'IN_PROGRESS',startedAt:'2026-01-01T00:00:00Z',endedAt:null,
 serverNow:'2026-01-01T01:03:12Z',teams:[{teamId:'left',teamName:'Левая',photoUrl:null,score:7},
 {teamId:'right',teamName:'Правая',photoUrl:'/uploads/right.png',score:9}],profile:defaultProfile()});
test('clock uses actual wall duration including pauses and unbounded minutes, never page-load start',()=>{
 const s=sample();assert.equal(elapsedLabel(s,Date.parse(s.serverNow)),'63:12');
 assert.equal(elapsedLabel({...s,status:'PLANNED',startedAt:null},Date.parse(s.serverNow)),'00:00');
 assert.equal(elapsedLabel(s,Date.parse(s.startedAt)-1000),'00:00');
 const finished={...s,status:'FINISHED',endedAt:'2026-01-01T01:03:12Z'};
 assert.equal(elapsedLabel(finished,Date.parse('2026-12-31T23:59:59Z')),'63:12');
 assert.equal(elapsedLabel({...finished,teams:[{...s.teams[0],score:6},s.teams[1]]},0),'63:12');
});
test('monotonic clock anchor ignores workstation wall-clock jumps and resynchronizes to persisted finish',()=>{
 let mono=100;const clock=new ClockAnchor(()=>mono);clock.accept(sample());
 mono+=3000;assert.equal(clock.label(),'63:15');
 clock.accept({...sample(),status:'FINISHED',endedAt:'2026-01-01T01:03:12Z'});
 mono+=60000;assert.equal(clock.label(),'63:12');
 const reopened=new ClockAnchor(()=>9000);reopened.accept(sample());assert.equal(reopened.label(),'63:12');
});
test('swap moves entire team identity score image color and short name without mutating canonical data',()=>{
 const s=sample(),original=structuredClone(s),profile={shortNames:{left:'Л'},teamColors:{left:'#ffffff'},swapped:true};
 const view=scoreboardTeams(s,profile);
 assert.equal(view[0].teamId,'right');assert.equal(view[0].photoUrl,'/uploads/right.png');assert.equal(view[0].score,9);
 assert.equal(view[1].teamName,'Л');assert.equal(view[1].color,'#ffffff');assert.equal(view[1].score,7);
 assert.deepEqual(s,original);assert.deepEqual(scoreboardTeams(s,defaultProfile()).map(t=>t.teamName),['Левая','Правая']);
});
class Source {
 static instances=[];
 constructor(id){this.id=id;this.listeners={};Source.instances.push(this);}
 addEventListener(name,fn){this.listeners[name]=fn;}
 emit(name){this.listeners[name]?.();}
 close(){this.closed=true;}
}
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
test('terminal scoreboard stays subscribed and reconciles late corrections and reconnect without dropping known state',async()=>{
 let state=sample(),fail=false;const seen=[],statuses=[];
 const stop=watchScoreboard('a',s=>seen.push(s),s=>statuses.push(s),{Source,pollMs:100000,retryMs:5,
  fetchSnapshot:async()=>{if(fail)throw Error('offline');return structuredClone(state);}});
 const source=Source.instances.at(-1);await pause(0);assert.equal(seen.at(-1).teams[0].score,7);
 state={...state,status:'FINISHED',endedAt:'2026-01-01T01:03:12Z'};source.emit('match-finished');await pause(0);
 assert.equal(seen.at(-1).status,'FINISHED');assert.equal(source.closed,undefined);
 state.teams[0].score=6;source.emit('event-log-changed');await pause(0);assert.equal(seen.at(-1).teams[0].score,6);
 fail=true;source.emit('connected');await pause(2);assert.equal(seen.at(-1).teams[0].score,6);
 assert.ok(statuses.some(s=>s.includes('offline')));
 fail=false;state.teams[0].score=8;await pause(15);assert.equal(seen.at(-1).teams[0].score,8);
 stop();assert.equal(source.closed,true);
});
test('bounded fetch timeout retries and disposal aborts outstanding work with no late snapshot',async()=>{
 const priorFetch=globalThis.fetch;let calls=0,aborts=0;const seen=[];
 globalThis.fetch=async(_url,{signal})=>{calls++;return new Promise((_resolve,reject)=>{
  signal.addEventListener('abort',()=>{aborts++;reject(Error('timed out'));},{once:true});
 });};
 try {
  const stop=watchScoreboard('a',s=>seen.push(s),()=>{},{Source,pollMs:100000,timeoutMs:10,retryMs:5});
  await pause(30);assert.ok(calls>=2);assert.ok(aborts>=1);
  stop();const count=calls;await pause(25);assert.equal(calls,count);assert.equal(seen.length,0);
 }finally{globalThis.fetch=priorFetch;}
});
