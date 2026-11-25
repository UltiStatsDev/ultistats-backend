import test from 'node:test';
import assert from 'node:assert/strict';
import {createActiveLinks, validateCurrent} from '../../main/resources/static/obs/active-links.mjs';
const match='814a82aa-0bd9-4eb9-acce-05d7df2722f9', key='1cd250e8-7d11-4b9a-b932-09d67c3dab98';
const origin='https://fixture.invalid';
function active(scope='AUTO_SCOREBOARD',id=null) {
 const kind=scope.endsWith('SCOREBOARD')?'scoreboard':scope.endsWith('EVENT_LOG')?'event-log':'infographics';
 return {status:'ACTIVE',key:{keyId:key,scope,matchId:id,createdAt:'2026-11-21T00:00:00Z',revokedAt:null},url:`${origin}/obs-sources/${kind}.html?${id?'matchId='+id:'mode=auto'}#key=${'A'.repeat(43)}`};
}
const response=(data,status=200)=>({ok:status<400,status,json:async()=>data});
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {resolve,promise};};
function fixture() {
 let current=true,listener=()=>{},handler=async()=>response({status:'NO_ACTIVE_LINK',key:null,url:null}),ids=0;
 const calls=[],states=[];
 const session={capture:()=>({id:1}),isCurrent:()=>current,subscribe:fn=>{listener=fn;return ()=>{};},protectedFetch:async(path,init)=>{calls.push({path,...init});return handler(path,init);}};
 const controller=createActiveLinks({session,onChange:state=>states.push(state),randomId:()=>`op-${++ids}`});
 return {controller,calls,states,setHandler:fn=>handler=fn,expire:()=>{current=false;listener();}};
}
test('current validates all six exact/auto bindings and generated HTTPS shape',()=>{
 for(const kind of ['scoreboard','event-log','infographics'])for(const id of [null,match]) {
  const binding={kind,matchId:id,scope:`${id?'EXACT':'AUTO'}_${kind==='scoreboard'?'SCOREBOARD':kind==='event-log'?'EVENT_LOG':'INFOGRAPHICS'}`};
  assert.equal(validateCurrent(active(binding.scope,id),binding).status,'ACTIVE');
  for(const change of [{url:active(binding.scope,id).url.replace('https:','http:')},{key:{...active(binding.scope,id).key,scope:'WRONG'}},{url:active(binding.scope,id).url+'&extra=1'},{status:'UNKNOWN'},{key:{...active(binding.scope,id).key,revokedAt:'2026-01-01'}}])
   assert.throws(()=>validateCurrent({...active(binding.scope,id),...change},binding));
 }
});
test('selection reads current only, preview is keyless, reopening restores without POST',async()=>{
 const f=fixture();f.setHandler(async(path)=>response(path.includes('EVENT_LOG')?active('AUTO_EVENT_LOG'):active()));
 await f.controller.select(null);
 assert.equal(f.controller.state.cards.scoreboard.url,active().url);
 assert.equal(f.controller.preview('event-log'),'/obs-preview/event-log.html?mode=auto');
 await f.controller.select(match);assert.equal(f.controller.preview('scoreboard'),`/obs-preview/scoreboard.html?matchId=${match}`);
 await f.controller.select(null);assert.equal(f.calls.length,9);assert(f.calls.every(c=>c.method==='GET'));
});
test('ambiguous issue retry keeps operationId per binding without another rotation intent',async()=>{
 const f=fixture();await f.controller.select(null);
 let fail=true;f.setHandler(async()=>{if(fail)throw Error('network');return response(active(),201);});
 await f.controller.issue('scoreboard');assert.equal(f.controller.state.cards.scoreboard.status,'UNKNOWN');
 const first=JSON.parse(f.calls.at(-1).body);assert.deepEqual(Object.keys(first).sort(),['operationId','scope']);
 fail=false;await f.controller.issue('scoreboard');assert.equal(JSON.parse(f.calls.at(-1).body).operationId,first.operationId);
 assert.equal(f.controller.state.cards.scoreboard.status,'ACTIVE');
 await f.controller.issue('scoreboard');assert.notEqual(JSON.parse(f.calls.at(-1).body).operationId,first.operationId);
});
test('late response JSON and old context cannot replace a new selection',async()=>{
 const f=fixture(),json=deferred();await f.controller.select(null);
 f.setHandler(async()=>({ok:true,status:201,json:()=>json.promise}));
 const issue=f.controller.issue('scoreboard');await new Promise(r=>setImmediate(r));
 f.setHandler(async()=>response({status:'NO_ACTIVE_LINK',key:null,url:null}));await f.controller.select(match);
 json.resolve(active());await issue;
 assert.equal(f.controller.state.matchId,match);assert.equal(f.controller.state.cards.scoreboard.status,'NO_ACTIVE_LINK');
});
test('revoke captures shown key and context, stale confirmation sends no request',async()=>{
 const f=fixture();f.setHandler(async(path)=>response(path.includes('EVENT_LOG')?active('AUTO_EVENT_LOG'):active()));await f.controller.select(null);
 const intent=f.controller.revokeIntent('scoreboard');await f.controller.select(match);const count=f.calls.length;
 await f.controller.revoke(intent);assert.equal(f.calls.length,count);
 await f.controller.select(null);const selected=f.controller.revokeIntent('scoreboard');f.setHandler(async(path,init)=>init.method==='DELETE'?response(null,204):response({status:'NO_ACTIVE_LINK',key:null,url:null}));
 await f.controller.revoke(selected);assert.equal(f.calls.at(-2).path,`/api/v1/obs/source-keys/${key}`);
 assert.equal(f.controller.state.cards.scoreboard.status,'NO_ACTIVE_LINK');assert.equal(f.controller.state.cards['event-log'].status,'ACTIVE');
});
test('legacy, unavailable material, and unknown DELETE are distinct and never mutate on current',async()=>{
 const f=fixture();f.setHandler(async()=>response({...active(),status:'REISSUE_REQUIRED',url:null}));await f.controller.select(null);
 assert.equal(f.controller.state.cards.scoreboard.status,'REISSUE_REQUIRED');
 f.setHandler(async()=>response({},503));await f.controller.select(null);assert.equal(f.controller.state.cards.scoreboard.status,'UNAVAILABLE');
 f.setHandler(async(path)=>response(path.includes('EVENT_LOG')?active('AUTO_EVENT_LOG'):active()));await f.controller.select(null);
 const intent=f.controller.revokeIntent('scoreboard');f.setHandler(async()=>{throw Error('network');});await f.controller.revoke(intent);
 assert.equal(f.controller.state.cards.scoreboard.status,'UNKNOWN');assert.equal(f.controller.state.cards.scoreboard.url,'');
 assert.equal(f.calls.filter(c=>c.method==='POST').length,0);
});
test('captured session stops writes and clears secrets even after awaited JSON; dispose ends updates',async()=>{
 const f=fixture();f.setHandler(async(path)=>response(path.includes('EVENT_LOG')?active('AUTO_EVENT_LOG'):active()));await f.controller.select(null);
 const json=deferred();f.setHandler(async()=>({ok:true,status:201,json:()=>json.promise}));const pending=f.controller.issue('scoreboard');await new Promise(r=>setImmediate(r));
 f.expire();json.resolve(active());await pending;
 assert.equal(f.controller.state.current,false);assert.equal(f.controller.state.cards.scoreboard.url,'');
 const count=f.calls.length;await f.controller.issue('event-log');assert.equal(f.calls.length,count);
 f.controller.dispose();await f.controller.select(match);assert.equal(f.calls.length,count);
});
test('DOM copy reports denial fallback, opens captured confirmation and clears secret iframe on session change',async()=>{
 const {mountActiveLinks}=await import('../../main/resources/static/obs/active-links-admin.mjs');
 const elements=new Map(),events={};
 const element=id=>elements.get(id)??elements.set(id,{value:'',textContent:'',disabled:false,src:'',hidden:false,selected:false,focus(){},select(){this.selected=true;},showModal(){this.open=true;},close(){this.open=false;},addEventListener(type,fn){this[type]=fn;}}).get(id);
 const document={getElementById:element},session=fixture();
 let current=true,notice;
 const fake={capture:()=>1,isCurrent:()=>current,subscribe:fn=>{notice=fn;return ()=>{};},protectedFetch:async(path)=>response(path.includes('EVENT_LOG')?active('AUTO_EVENT_LOG'):active())};
 element('links-mode').value='auto';element('links-kind').value='scoreboard';
 const control=mountActiveLinks(fake,{document,clipboard:{writeText:async()=>{throw Error('denied');}},listen:(event,fn)=>events[event]=fn});
 await new Promise(r=>setImmediate(r));await element('link-scoreboard-copy').click();
 assert.equal(element('link-scoreboard-url').selected,true);assert.match(element('link-scoreboard-status').textContent,/выделенную/);
 await element('link-scoreboard-revoke').click();assert.equal(element('links-dialog').open,true);
 current=false;notice();assert.equal(element('links-dialog').open,false);assert.equal(element('link-scoreboard-url').value,'');assert.equal(element('links-preview').src,'about:blank');
 assert.equal(element('link-scoreboard-issue').disabled,true);events.pagehide();control.dispose();
});
test('actual Jackson ACTIVE metadata may omit revokedAt while a non-null revocation is rejected',()=>{
 const data=active();delete data.key.revokedAt;
 assert.equal(validateCurrent(data,{kind:'scoreboard',scope:'AUTO_SCOREBOARD',matchId:null}).status,'ACTIVE');
});
test('502 after committed rotation retains intent and reread cannot make a still-pending binding copyable/revocable',async()=>{
 const f=fixture();await f.controller.select(null);f.setHandler(async()=>response({},502));
 await f.controller.issue('scoreboard');const op=JSON.parse(f.calls.at(-1).body).operationId;
 assert.equal(f.controller.state.cards.scoreboard.retry,true);
 f.setHandler(async(path,init)=>response(init.method==='POST'?active():path.includes('EVENT_LOG')?active('AUTO_EVENT_LOG'):active()));
 await f.controller.select(null);assert.equal(f.controller.state.cards.scoreboard.url,'');assert.equal(f.controller.revokeIntent('scoreboard'),null);
 await f.controller.issue('scoreboard');assert.equal(JSON.parse(f.calls.at(-1).body).operationId,op);assert.equal(f.controller.state.cards.scoreboard.status,'ACTIVE');
});
test('DELETE of displayed old key rereads actual current instead of concealing another-tab replacement',async()=>{
 const f=fixture();f.setHandler(async path=>response(path.includes('EVENT_LOG')?active('AUTO_EVENT_LOG'):active()));await f.controller.select(null);
 const intent=f.controller.revokeIntent('scoreboard'),replacement=active();replacement.key={...replacement.key,keyId:'2ae4da9a-213b-4445-9e49-d6fe86bba3f5'};
 f.setHandler(async(path,init)=>init.method==='DELETE'?response(null,204):response(replacement));await f.controller.revoke(intent);
 assert.equal(f.controller.state.cards.scoreboard.key.keyId,replacement.key.keyId);assert.equal(f.controller.state.cards.scoreboard.status,'ACTIVE');
 assert.equal(f.calls.at(-2).path,`/api/v1/obs/source-keys/${key}`);
});
test('late DOM selection completion cannot relabel a newer AUTO context as another match',async()=>{
 const {mountActiveLinks}=await import('../../main/resources/static/obs/active-links-admin.mjs');
 const elements=new Map();const element=id=>elements.get(id)??elements.set(id,{value:'',textContent:'',addEventListener(type,fn){this[type]=fn;},close(){},showModal(){}}).get(id);
 let wait=false;const gates=[];
 const session={capture:()=>1,isCurrent:()=>true,subscribe:()=>()=>{},protectedFetch:async(path)=>{
  if(wait){const gate=deferred();gates.push(gate);return gate.promise;}
  return response(path.includes('EVENT_LOG')?active('AUTO_EVENT_LOG'):active());
 }};
 element('links-mode').value='auto';element('links-kind').value='scoreboard';
 const control=mountActiveLinks(session,{document:{getElementById:element},listen:()=>{}});await new Promise(r=>setImmediate(r));
 wait=true;element('links-mode').value='exact';element('links-match').value=match;const first=element('links-open').click();
 wait=false;element('links-mode').value='auto';element('links-mode').change();await new Promise(r=>setImmediate(r));
 gates.forEach(g=>g.resolve(response(active())));await first;
 assert.match(element('links-context').textContent,/Автоматический/);control.dispose();
});
test('named match link selector opens EXACT without changing explicitly opened editors',async()=>{
 const {mountActiveLinks}=await import('../../main/resources/static/obs/active-links-admin.mjs');
 const elements=new Map();const element=id=>elements.get(id)??elements.set(id,{value:'',textContent:'',addEventListener(type,fn){this[type]=fn;},close(){},showModal(){}}).get(id);
 const calls=[];const session={capture:()=>1,isCurrent:()=>true,subscribe:()=>()=>{},protectedFetch:async path=>{calls.push(path);return response({status:'NO_ACTIVE_LINK',key:null,url:null});}};
 element('links-mode').value='auto';element('links-kind').value='scoreboard';element('match-id').value='2ae4da9a-213b-4445-9e49-d6fe86bba3f5';
 const control=mountActiveLinks(session,{document:{getElementById:element},listen:()=>{}});await new Promise(r=>setImmediate(r));
 element('links-matches').value=match;element('links-matches').selectedOptions=[{textContent:'Left — Right'}];
 element('links-matches').change();await new Promise(r=>setImmediate(r));
 assert.equal(control.state.matchId,match);assert.equal(element('links-mode').value,'exact');assert.equal(element('match-id').value,'2ae4da9a-213b-4445-9e49-d6fe86bba3f5');
 assert.ok(calls.slice(-2).every(path=>path.includes('/current/'+match)));control.dispose();
});
