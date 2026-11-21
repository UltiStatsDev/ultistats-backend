import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

for(const kind of ['scoreboard','event-log'])test(`${kind} editor messages require parent/origin/current capture; loss/pagehide clears and rejects late state`,async()=>{
 let accept,status,current=true;const events={},renders=[];
 const root={hidden:true,style:{},dataset:{},replaceChildren(){renders.push('clear')},querySelectorAll:()=>[]};
 const parent={postMessage(){}};
 const snapshot={matchId:'id',teams:[],profile:{swapped:false,shortNames:{},teamColors:{}},logRevision:1,events:[],retractions:[],config:{maxRows:5,ttlSeconds:8}};
 const source=(await readFile(new URL(`../../main/resources/static/obs/protected-${kind}.mjs`,import.meta.url),'utf8'))
  .replace(/^import .*;\n/gm,'').replace("await (await import('./staff-preview.mjs')).openPreview(url,",'await openPreview(url,');
 const context=vm.createContext({URL,Map,Object,Date,structuredClone,performance,innerWidth:1000,innerHeight:160,parent,window:{},
  document:{getElementById:()=>root},location:{href:`https://localhost/obs-editor/${kind}.html?matchId=id`,pathname:`/obs-editor/${kind}.html`,origin:'https://localhost'},
  addEventListener:(name,fn)=>events[name]=fn,removeEventListener(){},setInterval:()=>1,clearInterval(){},
  ClockAnchor:class{accept(){}label(){return '00:00'}},Feed:class{constructor(){this.config=snapshot.config}configure(c){this.config=c}reconcile(){}rows(){return[]}},
  Renderer:class{dispose(){renders.push('clear')}render(rows){renders.push(rows.length)}},eventLogMatch:s=>s,validateEventLog:s=>s,
  renderScoreboard:(root,s,p)=>{renders.push(p);return{}},parseSource(){throw Error('viewer parser cannot read editor')},
  openPreview:async()=>({source:{},transport:{isCurrent:()=>current,dispose(){}}}),
  watchProtectedSource:(s,a,b)=>{accept=a;status=b;return()=>{}},
 });
 await vm.runInContext(`(async()=>{${source}\n})()`,context);await Promise.resolve();
 assert.equal(typeof accept,'function');accept(snapshot);
 const event={origin:'https://localhost',source:parent,data:kind==='scoreboard'?{type:'scoreboard-draft',profile:{swapped:true,shortNames:{},teamColors:{}}}:{type:'obs-test'}};
 const before=renders.length;events.message({...event,source:{}});events.message({...event,origin:'https://foreign'});assert.equal(renders.length,before);
 events.message(event);assert.ok(renders.length>before);if(kind==='scoreboard')assert.equal(renders.at(-1).swapped,true);else assert.equal(renders.at(-1),1);
 current=false;accept(null);const ended=renders.length;events.message(event);assert.equal(renders.length,ended);
 events.pagehide();const hidden=renders.length;accept(snapshot);events.message(event);assert.equal(renders.length,hidden);
});
