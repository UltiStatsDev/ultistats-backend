import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

test('context rebind makes late reads stale, restores the original draft and seals it on session loss',async()=>exercise(true));

test('session loss seals the typed scoreboard draft while clearing frame and rejecting late reads/writes/messages',async()=>exercise(false));

async function exercise(rebind){
 const ids=new Map(),created=[];let current=true,listener,deliver,writes=0,posts=0;
 function element(tag='div') {return {tagName:tag.toUpperCase(),dataset:{},style:{},children:[],listeners:{},value:'',
  append(...v){this.children.push(...v)},replaceChildren(...v){this.children=v},addEventListener(n,f){(this.listeners[n]??=[]).push(f)},querySelector(){return null},contentWindow:{postMessage(){posts++}}};}
 const $=id=>{if(!ids.has(id))ids.set(id,element());return ids.get(id)};
 const snapshot={teams:[{teamId:'a',teamName:'Left',photoUrl:null},{teamId:'b',teamName:'Right',photoUrl:null}],profile:{swapped:false,shortNames:{},teamColors:{}}};
 const session={subscribe:fn=>listener=fn,protectedFetch:async()=>{writes++;return {ok:true,json:async()=>snapshot.profile}}};
 const events={};const context=vm.createContext({document:{getElementById:$,createElement:tag=>{const e=element(tag);created.push(e);return e}},location:{origin:'https://localhost'},AbortController,structuredClone,Map,obsSession:session,obsPageCurrent:()=>current,assertObsPage:()=>{if(!current)throw Error('session changed')},
  addEventListener:(n,f)=>events[n]=f,watchStaffJson:(s,p,fn)=>{deliver=fn;return()=>{}},defaultProfile:()=>({swapped:false,shortNames:{},teamColors:{}}),validateSnapshot(){},teamAccent:()=> '#ffffff',imageAccent:async()=> '#ffffff'});
 const source=(await readFile(new URL('../../main/resources/static/obs/scoreboard-admin.mjs',import.meta.url),'utf8')).replace(/^import .*;\n/gm,'').replace(/export /g,'');
 vm.runInContext(source,context);context.bindScoreboardSession();context.mountScoreboardAdmin('814a82aa-0bd9-4eb9-acce-05d7df2722f9');deliver(snapshot);
 let name=created.find(e=>e.dataset.scoreboardName==='a');name.value='Typed draft';name.listeners.input[0]();
 if(rebind){
  const stale=deliver;
  context.mountScoreboardAdmin('9f3c93fe-f56c-4045-b593-6066bd17242e');
  stale(snapshot);
  assert.equal($('scoreboard-fields').disabled,true);
  const other={...snapshot,profile:{...snapshot.profile,shortNames:{a:'Other saved'}}};
  deliver(other);
  assert.equal(created.findLast(e=>e.dataset.scoreboardName==='a').value,'Other saved');
  context.mountScoreboardAdmin('814a82aa-0bd9-4eb9-acce-05d7df2722f9');deliver(snapshot);
  name=created.findLast(e=>e.dataset.scoreboardName==='a');
  assert.equal(name.value,'Typed draft');
 }
 const rows=$('scoreboard-teams').children;current=false;listener();const before=posts;
 assert.equal($('scoreboard-fields').disabled,true);assert.equal($('scoreboard-preview').src,'about:blank');assert.equal($('scoreboard-teams').children,rows);assert.equal(name.value,'Typed draft');
 deliver(snapshot);$('scoreboard-preview').listeners.load[0]();await $('scoreboard-form').listeners.submit[0]({preventDefault(){}});
 assert.equal(posts,before);assert.equal(writes,0);assert.equal($('scoreboard-teams').children,rows);
 events.pagehide();assert.equal($('scoreboard-teams').children.length,0);
}
