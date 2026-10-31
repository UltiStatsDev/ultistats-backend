const {createProxy}=require('./fault-proxy.cjs');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs'),{createHash}=require('node:crypto');
const api='http://127.0.0.1:19856',base='http://127.0.0.1:19857',output=process.env.OBS147_OUTPUT||'/tmp/obs147-runtime/browser';fs.mkdirSync(output,{recursive:true});
const pause=ms=>new Promise(r=>setTimeout(r,ms));
async function request(origin,path,method='GET',body){const r=await fetch(origin+path,{method,headers:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});const text=await r.text();assert.ok(r.ok,`${r.status} ${text}`);return text?JSON.parse(text):null;}
const req=(p,m,b)=>request(api,p,m,b),fault=value=>request(base,'/_test/fault','PUT',value);
async function until(read,want,ms=12000){const start=performance.now();let value;do{value=await read();if(want(value))return performance.now()-start;await pause(40);}while(performance.now()-start<ms);throw Error('condition timeout '+JSON.stringify(value));}
async function workerSession(browser){const root=await browser.newBrowserCDPSession();const targets=()=>root.send('Target.getTargets').then(r=>r.targetInfos.filter(t=>t.type==='shared_worker'&&t.url===base+'/obs/shared-worker.js'));
 await until(targets,v=>v.length===1);const {sessionId}=await root.send('Target.attachToTarget',{targetId:(await targets())[0].targetId,flatten:false});let id=0;
 return expression=>new Promise((resolve,reject)=>{const next=++id,timer=setTimeout(()=>{root.off('Target.receivedMessageFromTarget',listener);reject(Error('CDP timeout'));},3000);
 function listener(e){if(e.sessionId!==sessionId)return;const r=JSON.parse(e.message);if(r.id!==next)return;clearTimeout(timer);root.off('Target.receivedMessageFromTarget',listener);r.error||r.result?.exceptionDetails?reject(Error(JSON.stringify(r))):resolve(r.result.result.value);}
 root.on('Target.receivedMessageFromTarget',listener);root.send('Target.sendMessageToTarget',{sessionId,message:JSON.stringify({id:next,method:'Runtime.evaluate',params:{expression,returnByValue:true}})}).catch(reject);});
}
(async()=>{
 const proxy=createProxy();await proxy.listen();const browser=await chromium.launch({executablePath:'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
 let initialConfig;
 try{
 initialConfig=await req('/api/v1/obs/config');
 const backendAsset=Buffer.from(await(await fetch(api+'/obs/shared-worker.js')).arrayBuffer()),forwardedAsset=Buffer.from(await(await fetch(base+'/obs/shared-worker.js')).arrayBuffer());assert.deepEqual(forwardedAsset,backendAsset,'proxy must forward successful assets unchanged');
 const teams=await Promise.all(['147 Home','147 Away'].map(name=>req('/api/v1/teams','POST',{name}))),matches=[];
 for(let i=0;i<2;i++){const m=await req('/api/v1/matches','POST',{teamIds:teams.map(t=>t.id),playersPerTeam:2});await req(`/api/v1/matches/${m.id}/start`,'POST',{timestamp:new Date(Date.now()-60000).toISOString()});matches.push(await req(`/api/v1/matches/${m.id}`));}
 const scenario=async initial=>{
  const context=await browser.newContext({viewport:{width:1000,height:160}}),pages=[];const errors=[];context.on('page',p=>p.on('pageerror',e=>errors.push(e.message)));
  await fault({mode:initial?'503':'healthy'});const offset=proxy.state.attempts.length;
  const log=await context.newPage();await log.goto(base+'/obs-battle-log.html?matchId='+matches[0].id);await log.locator('#feed').waitFor({state:'attached'});
  for(let i=0;i<4;i++){const p=await context.newPage();await p.goto(base+'/obs-scoreboard.html?matchId='+matches[i%2].id);await p.locator('.scoreboard-score').waitFor();pages.push(p);}
  const admin=await context.newPage();await admin.goto(base+'/obs-admin.html');await admin.locator('#match-id').fill(matches[0].id);await admin.locator('#open-match').click();await admin.frameLocator('#scoreboard-preview').locator('.scoreboard-score').waitFor();
  const evaluate=await workerSession(browser);
  if(!initial){await until(()=>proxy.state.active,n=>n===1);await fault({mode:'503',drop:true});}
  await until(()=>proxy.state.attempts.slice(offset).some(a=>a.status===503),Boolean);
  const closed=await evaluate('source?.readyState??null');
  await fault({mode:'healthy'});
  try{await until(()=>proxy.state.active,n=>n===1,10000);}catch(error){fs.writeFileSync(output+(initial?'/initial-red.json':'/connected-red.json'),JSON.stringify({closed,trace:proxy.state,errors},null,2));throw error;}
  assert.equal(await evaluate('source.readyState'),1,'verified live SSE, not scoreboard polling');
  await evaluate(`globalThis.__obs147Frames=[];source.addEventListener('event-log-changed',event=>{__obs147Frames.push(JSON.parse(event.data));});true`);
  const m=matches[0],a=m.teams[0].participants,b=m.teams[1].participants;let acceptedGoal;
  for(const [n,type,fields]of [[0,'PULL',{participantId:a[0].participantId}],[1,'PICKUP',{participantId:b[0].participantId}],[2,'GOAL',{fromParticipantId:b[0].participantId,toParticipantId:b[1].participantId}]]){const saved=await req(`/api/v1/matches/${m.id}/events`,'POST',{type,occurredAt:new Date(Date.now()+n).toISOString(),...fields});if(type==='GOAL')acceptedGoal=saved;}
  const eventMs=await until(()=>log.locator('.feed-row:not(.leaving)').count(),n=>n>=1,2000);
  const recoveredGoalId=acceptedGoal.id;await until(()=>evaluate('__obs147Frames'),frames=>frames.some(f=>f.matchId===m.id&&f.eventId===recoveredGoalId&&f.operation==='CREATED'),2000);
  const snapshot=await req(`/api/v1/obs/scoreboard/${m.id}`);await until(()=>pages[0].locator('.scoreboard-score').textContent(),s=>s===`${snapshot.teams[0].score} : ${snapshot.teams[1].score}`,2000);
  const beforeOther=await pages[1].locator('.scoreboard-name').first().textContent();
  await req(`/api/v1/obs/scoreboard/${m.id}/profile`,'PUT',{shortNames:{[teams[0].id]:'RECOVERED'},teamColors:{},swapped:false});await until(()=>pages[0].locator('.scoreboard-name').first().textContent(),s=>s==='RECOVERED',2000);assert.equal(await pages[1].locator('.scoreboard-name').first().textContent(),beforeOther);
  const config=await req('/api/v1/obs/config');await req('/api/v1/obs/config','PUT',{...config,corner:config.corner==='BOTTOM_LEFT'?'TOP_RIGHT':'BOTTOM_LEFT'});await until(()=>log.locator('#feed').getAttribute('data-corner'),s=>s!==config.corner,2000);await req('/api/v1/obs/config','PUT',config);
  // A healthy shared SSE must remain live during one hung scoreboard read; polling alone is insufficient.
  const hungBefore=proxy.state.hungReads;await fault({mode:'healthy',hangNext:true});let marked=false;
  await pages[0].route(`**/api/v1/obs/scoreboard/${m.id}`,route=>{if(!marked){marked=true;return route.continue({headers:{...route.request().headers(),'x-obs147-hang':'once'}});}return route.continue();});
  await until(()=>proxy.state.hungReads,n=>n===hungBefore+1,4000);
  assert.equal(proxy.state.active,1);assert.equal(await evaluate('source.readyState'),1);
  for(const [n,type,fields]of [[0,'PULL',{participantId:a[0].participantId}],[1,'PICKUP',{participantId:b[0].participantId}],[2,'GOAL',{fromParticipantId:b[0].participantId,toParticipantId:b[1].participantId}]]){const saved=await req(`/api/v1/matches/${m.id}/events`,'POST',{type,occurredAt:new Date(Date.now()+n).toISOString(),...fields});if(type==='GOAL')acceptedGoal=saved;}
  const urgentMs=await until(()=>pages[0].locator('.scoreboard-score').textContent(),s=>s===`${snapshot.teams[0].score} : ${snapshot.teams[1].score+1}`,2000);
  await until(()=>log.locator('.feed-row:not(.leaving)').count(),n=>n>=2,2000);
  assert.equal(await evaluate('source.readyState'),1);assert.equal(proxy.state.active,1);
  const urgentGoalId=acceptedGoal.id;await until(()=>evaluate('__obs147Frames'),frames=>frames.some(f=>f.matchId===m.id&&f.eventId===urgentGoalId&&f.operation==='CREATED'),2000);
  const matchingFrames=(await evaluate('__obs147Frames')).filter(f=>f.eventId===recoveredGoalId||f.eventId===urgentGoalId);
  await log.screenshot({path:output+(initial?'/initial-eventlog.png':'/connected-eventlog.png'),omitBackground:true});
  const liveSSE=await evaluate('source.readyState');await fault({mode:'503',drop:true});await until(()=>proxy.state.attempts.at(-1)?.status,n=>n===503);await context.close();
  await until(()=>proxy.state.active,n=>n===0);const attempts=proxy.state.attempts.length;await pause(2500);assert.equal(proxy.state.attempts.length,attempts,'last subscriber cancels retry');
  assert.deepEqual(errors,[]);return {initial,closedBeforeRestore:closed,liveSSE,eventMs,urgentMs,recoveredGoalId,urgentGoalId,matchingFrames,attempts:proxy.state.attempts.slice(offset),peak:proxy.state.peak,cleanup:true};
 };
 const scenarios=process.env.OBS147_SCENARIO==='initial'?{initial:await scenario(true)}:{connected:await scenario(false),initial:await scenario(true)};assert.equal(proxy.state.peak,1);assert.deepEqual(await req('/api/v1/obs/config'),initialConfig);const result={api,proxy:base,matches:matches.map(m=>m.id),teams:teams.map(t=>t.id),forwardedAssetSha:createHash('sha256').update(forwardedAsset).digest('hex'),globalConfigUnchanged:true,...scenarios};fs.writeFileSync(output+'/result.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
 }finally{try{if(initialConfig&&JSON.stringify(await req('/api/v1/obs/config'))!==JSON.stringify(initialConfig))await req('/api/v1/obs/config','PUT',initialConfig);}finally{await browser.close();await proxy.close();}}
})().catch(e=>{console.error(e);process.exitCode=1;});
