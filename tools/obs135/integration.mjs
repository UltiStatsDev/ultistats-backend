// Dedicated synthetic API only: creates fixtures and leaves canonical end states for QA.
import {createRequire} from 'node:module';
import {randomUUID} from 'node:crypto';
import {mkdirSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const require=createRequire(import.meta.url),{chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const base=process.env.OBS135_BASE||'http://127.0.0.1:19835',output=process.env.OBS135_OUTPUT||'/tmp/obs135-runtime/integration';
mkdirSync(output,{recursive:true});
async function req(path,method='GET',body){const response=await fetch(base+path,{method,headers:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
 const text=await response.text();assert.ok(response.ok,`${method} ${path}: ${response.status} ${text}`);return text?JSON.parse(text):null;}
const pause=ms=>new Promise(r=>setTimeout(r,ms));
async function until(read,accept,timeout=2000){const begin=performance.now();let value;do{value=await read();if(accept(value))return performance.now()-begin;await pause(25);}while(performance.now()-begin<timeout);assert.fail('timeout '+JSON.stringify(value));}
const names=['КРАСНОЯРСК Очень длинное кириллическое название команды','СОПЕРНИКИ'];
const teams=await Promise.all(names.map(name=>req('/api/v1/teams','POST',{name})));
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
const context=await browser.newContext({viewport:{width:1000,height:160}}),errors=[];
context.on('page',p=>p.on('pageerror',e=>errors.push(e.message)));
const pages=[],sla={};
try {
 // Real uploaded non-square logo, red opaque center and transparent edges, produced in browser canvas.
 const painter=await context.newPage();const png=await painter.evaluate(()=>{
  const c=document.createElement('canvas');c.width=160;c.height=48;const x=c.getContext('2d');
  x.fillStyle='#e03424';x.fillRect(0,0,160,48);x.fillStyle='#ffffff';x.fillRect(10,8,140,32);return c.toDataURL('image/png').split(',')[1];
 });await painter.close();
 const form=new FormData();form.append('file',new Blob([Buffer.from(png,'base64')],{type:'image/png'}),'synthetic-wide-logo.png');
 const upload=await fetch(`${base}/api/v1/teams/${teams[0].id}/photo`,{method:'PUT',body:form});assert.ok(upload.ok,await upload.text());
 const matches=[];for(let i=0;i<2;i++)matches.push(await req('/api/v1/matches','POST',{teamIds:teams.map(t=>t.id),playersPerTeam:2}));
 const match=matches[0],other=matches[1],path=`/api/v1/matches/${match.id}`,events=path+'/events';
 const root=await browser.newBrowserCDPSession();
 const targets=async()=>(await root.send('Target.getTargets')).targetInfos.filter(t=>t.type==='shared_worker'&&t.url===base+'/obs/shared-worker.js');
 for(let i=0;i<6;i++){
  const id=i<4?match.id:other.id,input=i===1?id.toUpperCase():i===2?id.replace(/[a-f]/g,(c,index)=>index%2?c.toUpperCase():c):id;
  const page=await context.newPage();pages.push(page);await page.goto(`${base}/obs-scoreboard.html?matchId=${input}`);await page.locator('.scoreboard-score').waitFor();
 }
 const log=await context.newPage();await log.goto(`${base}/obs-battle-log.html?matchId=${match.id}`);await log.locator('#feed').waitFor({state:'attached'});
 const admin=await context.newPage();await admin.setViewportSize({width:1440,height:1100});await admin.goto(base+'/obs-admin.html');
 await admin.locator('#match-id').fill(match.id);await admin.locator('#open-match').click();await admin.frameLocator('#scoreboard-preview').locator('.scoreboard-score').waitFor();
 await until(targets,v=>v.length===1);
 const {sessionId}=await root.send('Target.attachToTarget',{targetId:(await targets())[0].targetId,flatten:false});let command=0;
 async function worker(expression){const id=++command;return new Promise((resolve,reject)=>{
  const timer=setTimeout(()=>{root.off('Target.receivedMessageFromTarget',listener);reject(Error('worker CDP timeout'));},3000);
  function listener(event){if(event.sessionId!==sessionId)return;const r=JSON.parse(event.message);if(r.id!==id)return;
   clearTimeout(timer);root.off('Target.receivedMessageFromTarget',listener);if(r.error||r.result?.exceptionDetails)reject(Error(JSON.stringify(r)));else resolve(r.result.result.value);}
  root.on('Target.receivedMessageFromTarget',listener);
  root.send('Target.sendMessageToTarget',{sessionId,message:JSON.stringify({id,method:'Runtime.evaluate',params:{expression,returnByValue:true}})}).catch(reject);
 });}
 const transport=await worker('({ports:clients.size,stream:source.url,readyState:source.readyState})');
 assert.ok(await worker(`([...clients.values()].every(c=>c.matchId===null||c.matchId==='${match.id}'||c.matchId==='${other.id}'))`),'all worker match subscriptions use canonical UUIDs');
 assert.equal(transport.readyState,1);assert.equal(transport.stream,base+'/api/v1/obs/stream');assert.equal((await targets()).length,1);
 const begin=performance.now();await Promise.all(pages.map(p=>p.evaluate(()=>fetch('/api/v1/obs/config').then(r=>{if(!r.ok)throw Error(r.status);return r.json();}))));const restMs=performance.now()-begin;assert.ok(restMs<2000);
 assert.equal(await pages[0].locator('.scoreboard-clock').textContent(),'00:00');assert.equal(await pages[0].locator('.scoreboard-score').textContent(),'0 : 0');
 await pages[0].locator('.scoreboard-logo img').waitFor();await until(()=>pages[0].locator('.scoreboard-logo img').evaluate(i=>i.complete&&i.naturalWidth>0),Boolean);
 const geometry=await pages[0].evaluate(()=>{
  const box=s=>{const r=document.querySelector(s).getBoundingClientRect();return {x:r.x,width:r.width,right:r.right};};
  return {image:{fit:getComputedStyle(document.querySelector('.scoreboard-logo img')).objectFit,width:document.querySelector('.scoreboard-logo img').naturalWidth,height:document.querySelector('.scoreboard-logo img').naturalHeight},
   name:box('.scoreboard-name'),score:box('.scoreboard-middle'),logo:box('.scoreboard-logo'),body:getComputedStyle(document.body).backgroundColor};
 });assert.equal(geometry.image.fit,'contain');assert.equal(geometry.image.width/geometry.image.height,160/48);assert.ok(geometry.name.right<=geometry.score.x);assert.equal(geometry.body,'rgba(0, 0, 0, 0)');
 assert.ok((await pages[0].locator('.scoreboard-initials').last().textContent()).length>0);
 // Manual white produces black text; auto extracts the existing logo, independent of Event Log config.
 await req(`/api/v1/obs/scoreboard/${match.id}/profile`,'PUT',{shortNames:{[teams[0].id]:'КРАС'},teamColors:{[teams[0].id]:'#ffffff'},swapped:false});
 await until(()=>pages[0].locator('.scoreboard-name').first().textContent(),v=>v==='КРАС');
 assert.equal(await pages[0].locator('.scoreboard-team').first().evaluate(n=>getComputedStyle(n).color),'rgb(0, 0, 0)');
 assert.equal(await pages[4].locator('.scoreboard-name').first().textContent(),names[0]);
 await req(`/api/v1/obs/scoreboard/${match.id}/profile`,'PUT',{});
 await until(()=>pages[0].locator('.scoreboard-team').first().evaluate(n=>getComputedStyle(n).backgroundColor),v=>v!=='rgb(255, 255, 255)');
 // PLANNED source receives actual start; >60 minutes uses server time, not page age.
 const started=Date.now()-3792000,stamp=seconds=>new Date(started+seconds*1000).toISOString();
 await req(path+'/start','POST',{timestamp:stamp(0)});
 sla.start=await until(()=>pages[0].locator('.scoreboard-clock').textContent(),v=>Number(v.split(':')[0])>=63);
 const current=await req(path),home=current.teams[0].participants.map(p=>p.participantId),away=current.teams[1].participants.map(p=>p.participantId);
 async function add(type,at,fields={}){return req(events,'POST',{type,occurredAt:stamp(at),...fields});}
 async function score(value,label){sla[label]=await until(()=>pages[0].locator('.scoreboard-score').textContent(),v=>v===value);
  await Promise.all(pages.slice(1,4).map(p=>until(()=>p.locator('.scoreboard-score').textContent(),v=>v===value)));}
 await add('PULL',1,{participantId:home[0]});await add('PICKUP',2,{participantId:away[0]});
 await add('TIMEOUT_START',10,{teamId:teams[1].id});await add('TIMEOUT_END',30,{teamId:teams[1].id});
 let goal=await add('GOAL',40,{fromParticipantId:away[0],toParticipantId:away[1]});await score('0 : 1','goal');
 await until(()=>log.locator('.feed-row:not(.leaving)').count(),v=>v===1);
 // A local pending goal object is never submitted and cannot alter server-derived scoreboard.
 await pages[0].evaluate(()=>{window.unsentLocalActions=[{type:'GOAL'}];});await pause(1200);assert.equal(await pages[0].locator('.scoreboard-score').textContent(),'0 : 1');
 await req(events+'/'+goal.id,'PATCH',{type:'GOAL',fromParticipantId:home[0],toParticipantId:home[1]});await score('1 : 0','update');
 await req(events+'/undo','POST');await score('0 : 0','undo');
 goal=await add('GOAL',41,{fromParticipantId:away[0],toParticipantId:away[1]});await score('0 : 1','goalAfterUndo');
 await add('HALFTIME_START',50);await add('HALFTIME_END',600);
 const position=await req(`/api/v2/matches/${match.id}/events`);
 const batch=[['PULL',{participantId:away[0]}],['PICKUP',{participantId:home[0]}],['GOAL',{fromParticipantId:home[0],toParticipantId:home[1]}]].map(([type,fields],i)=>({eventId:randomUUID(),type,occurredAt:stamp(620+i),...fields}));
 await req(`/api/v2/matches/${match.id}/events/batch`,'POST',{expectedLastSequenceNumber:position.lastSequenceNumber,expectedLogRevision:position.logRevision,events:batch});await score('1 : 1','batch');
 assert.equal(await pages[4].locator('.scoreboard-score').textContent(),'0 : 0');
 await pages[0].reload();await pages[0].locator('.scoreboard-clock').waitFor();assert.ok(Number((await pages[0].locator('.scoreboard-clock').textContent()).split(':')[0])>=63);
 // Stop network only for the browser. Server accepts finish while last known live clocks continue.
 await context.setOffline(true);const before=await pages[0].locator('.scoreboard-clock').textContent();await pause(2200);
 assert.equal(await pages[0].locator('.scoreboard-score').textContent(),'1 : 1');assert.notEqual(await pages[0].locator('.scoreboard-clock').textContent(),before);
 await req(path+'/end','POST',{timestamp:stamp(3792)});
 await context.setOffline(false);sla.reconnect=await until(()=>pages[0].locator('.scoreboard-clock').textContent(),v=>v==='63:12',2000);
 await pause(1200);assert.equal(await pages[0].locator('.scoreboard-clock').textContent(),'63:12');
 // Allowed correction of final GOAL reattributes score but never changes actual end duration.
 await req(events+'/'+batch[2].eventId,'PATCH',{type:'GOAL',fromParticipantId:away[0],toParticipantId:away[1]});await score('0 : 2','terminalCorrection');
 assert.equal(await pages[0].locator('.scoreboard-clock').textContent(),'63:12');await pages[0].reload();await pages[0].locator('.scoreboard-clock').waitFor();assert.equal(await pages[0].locator('.scoreboard-clock').textContent(),'63:12');
 // Initial failure never puts errors or stale teams on air, admin retains an actionable reason.
 const invalid=await context.newPage();await invalid.goto(base+'/obs-scoreboard.html?matchId=invalid');assert.equal(await invalid.locator('#scoreboard').isHidden(),true);
 const missing=randomUUID();await invalid.goto(base+'/obs-scoreboard.html?matchId='+missing);await pause(200);assert.equal(await invalid.locator('#scoreboard').isHidden(),true);
 await admin.locator('#match-id').fill(missing);await admin.locator('#open-match').click();await until(()=>admin.locator('#scoreboard-status').textContent(),v=>v.includes('404'));
 await invalid.close();
 const offline=await browser.newContext({viewport:{width:1000,height:160}});const unavailable=await offline.newPage();await unavailable.route('**/api/v1/obs/scoreboard/**',r=>r.abort('failed'));
 await unavailable.goto(base+'/obs-scoreboard.html?matchId='+match.id);await pause(150);assert.equal(await unavailable.locator('#scoreboard').isHidden(),true);await offline.close();
 await pages[0].screenshot({path:output+'/finished-logo.png',omitBackground:true});
 await pages[0].setViewportSize({width:500,height:80});await pages[0].screenshot({path:output+'/scaled.png',omitBackground:true});
 const saved={shortNames:{[teams[0].id]:'QA ЭФИР'},teamColors:{[teams[0].id]:'#ffcc00'},swapped:true};await req(`/api/v1/obs/scoreboard/${match.id}/profile`,'PUT',saved);
 await until(()=>pages[0].locator('.scoreboard-name').last().textContent(),v=>v==='QA ЭФИР');
 const final=await req(`/api/v1/obs/scoreboard/${match.id}`);assert.equal(final.endedAt,stamp(3792));assert.equal(final.teams[1].score,2);
 // Explicit pagehide disposal removes source clients and leaves no long-lived per-page SSE.
 await pages[5].close();await pause(100);const afterClose=await worker('clients.size');assert.ok(afterClose<transport.ports);
 for(const p of pages.slice(0,5))await p.close();await log.close();await admin.close();await pause(200);
 let cleanup;try{cleanup=await worker('({ports:clients.size,sourceClosed:source===null})');assert.equal(cleanup.ports,0);assert.equal(cleanup.sourceClosed,true);}catch(error){if((await targets()).length)throw error;cleanup={workerTerminated:true};}
 assert.deepEqual(errors,[]);const result={result:'PASS',base,matchId:match.id,otherMatchId:other.id,teams:teams.map(t=>t.id),startedAt:stamp(0),endedAt:stamp(3792),finalScore:'0:2',profile:saved,sla,transport,restMs,afterClose,cleanup,geometry,errors};
 writeFileSync(output+'/result.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
}finally{await browser.close();}
