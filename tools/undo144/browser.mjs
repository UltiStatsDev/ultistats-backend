// One browser batch, synthetic fixtures only; invoke under the bounded shared network lock.
import {createRequire} from 'node:module';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
import {randomUUID,createHash} from 'node:crypto';
const require=createRequire(import.meta.url);
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const base=process.env.UNDO144_BASE || 'http://127.0.0.1:19844';
const apiURL=new URL(base);
assert.equal(apiURL.protocol,'http:');assert.equal(apiURL.hostname,'127.0.0.1');
const apiPort=Number(apiURL.port || 80),proxyPort=Number(process.env.UNDO144_PROXY_PORT || 19845);
assert.ok(Number.isInteger(proxyPort) && proxyPort>0 && proxyPort<=65535);
const root=path.resolve(process.env.UNDO144_OUT || '/tmp/ultistats-undo144/browser');
fs.mkdirSync(root,{recursive:true});
const pause=ms=>new Promise(r=>setTimeout(r,ms));
async function until(check,timeout=5000){const start=Date.now();while(Date.now()-start<timeout){if(await check())return;await pause(20);}assert.fail('bounded condition timeout');}
async function api(route,method='GET',body){
 const response=await fetch(base+route,{method,headers:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
 const text=await response.text();assert.ok(response.ok,`${method} ${route}: ${response.status} ${text}`);return text?JSON.parse(text):null;
}
const owned=[];
async function create(kind,body){const result=await api('/api/v1/'+kind,'POST',body);owned.push({kind,id:result.id});fs.writeFileSync(path.join(root,'owned.json'),JSON.stringify(owned,null,2));return result;}
const token='Undo144-'+randomUUID().slice(0,8);
const teams=await Promise.all(['Home','Away'].map(side=>create('teams',{name:token+' '+side})));
const browser=await chromium.launch({headless:true, executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH});
const results=[];
try{
 for(const mode of ['headerless','partial']){
  const dir=path.join(root,mode);fs.mkdirSync(dir,{recursive:true});
  const match=await create('matches',{teamIds:teams.map(t=>t.id),playersPerTeam:2});
  const mid=match.id, at='2026-10-24T10:00:00Z';
  await api(`/api/v1/matches/${mid}/start`,'POST',{timestamp:at});
  const detail=await api(`/api/v1/matches/${mid}`);
  const home=detail.teams.find(t=>t.teamId===teams[0].id).participants.filter(p=>p.kind==='UNKNOWN');
  const away=detail.teams.find(t=>t.teamId===teams[1].id).participants.filter(p=>p.kind==='UNKNOWN');
  const events=[{eventId:randomUUID(),type:'PULL',occurredAt:at,participantId:away[0].participantId},
   {eventId:randomUUID(),type:'PICKUP',occurredAt:at,participantId:home[0].participantId},
   {eventId:randomUUID(),type:'PASS',occurredAt:at,fromParticipantId:home[0].participantId,toParticipantId:home[1].participantId}];
  await api(`/api/v2/matches/${mid}/events/batch`,'POST',{expectedLastSequenceNumber:0,expectedLogRevision:0,events});
  const before=await api(`/api/v2/matches/${mid}/events`);
  const fixture={matchId:mid,command:{commandId:randomUUID(),expectedLastEventId:events[2].eventId,expectedLogRevision:before.logRevision}};
  const fixtureFile=path.join(dir,'fixture.json');fs.writeFileSync(fixtureFile,JSON.stringify(fixture,null,2));
  fs.writeFileSync(path.join(dir,'before.json'),JSON.stringify(before,null,2));
  const proxy=spawn('python3',[fileURLToPath(new URL('fault_proxy.py',import.meta.url)),'--fixture',fixtureFile,'--out',dir,'--mode',mode,'--api-port',String(apiPort),'--port',String(proxyPort)],{stdio:['ignore','pipe','pipe']});
  let proxyOut='',proxyError='';proxy.stdout.on('data',d=>proxyOut+=d);proxy.stderr.on('data',d=>proxyError+=d);
  await until(()=>proxyOut.includes('ownedMatch') || proxy.exitCode!==null);
  assert.equal(proxy.exitCode,null,proxyError);
  const abort=new AbortController();let sse='';
  const stream=await fetch(base+`/api/v1/matches/${mid}/events/stream`,{signal:abort.signal});
  assert.equal(stream.status,200);
  const pump=(async()=>{try{for await(const chunk of stream.body)sse+=Buffer.from(chunk).toString();}catch(e){if(e.name!=='AbortError')throw e;}})();
  const page=await browser.newPage();
  try{
   await until(()=>sse.includes('event:connected'));
   await page.goto(`http://127.0.0.1:${proxyPort}/control`);
   await page.locator('#undo').waitFor({state:'visible'});
   await page.waitForFunction(()=>!document.querySelector('#undo').disabled);
   await page.locator('#undo').click();
   await page.waitForFunction(()=>document.querySelector('#result').textContent!=='Not started');
   assert.equal(await page.locator('#invocations').textContent(),'1');
   const browserResult=await page.locator('#result').textContent();
   await until(()=>sse.includes('event:event-log-changed'));
   await pause(200);
   const after=await api(`/api/v2/matches/${mid}/events`);
   const outcome=await api(`/api/v2/matches/${mid}/events/undo/${fixture.command.commandId}`);
   const committed=JSON.parse(fs.readFileSync(path.join(dir,'real-committed-outcome.json')));
   const incoming=fs.readFileSync(path.join(dir,'incoming.jsonl'),'utf8').trim().split('\n').map(JSON.parse);
   const posts=incoming.filter(r=>r.method==='POST');
   const warm=incoming.find(r=>r.method==='GET'&&r.path===`/api/v2/matches/${mid}/events`);
   assert.equal(posts.length,mode==='headerless'?2:1,JSON.stringify(incoming));
   assert.equal(posts[0].clientPort,warm.clientPort,'first POST uses warmed keepalive connection');
   if(mode==='headerless'){
    assert.notEqual(posts[0].clientPort,posts[1].clientPort);
    assert.deepEqual(posts[0].requestBody,posts[1].requestBody);
    assert.equal(posts[0].responseSha256,posts[1].responseSha256);
    assert.ok(browserResult.startsWith('HTTP 200\n'),browserResult);
    assert.deepEqual(JSON.parse(browserResult.slice('HTTP 200\n'.length)),outcome);
   }else{assert.ok(browserResult.includes('TypeError'),browserResult);}
   assert.deepEqual(after.events.map(e=>e.id),before.events.slice(0,2).map(e=>e.id));
   assert.equal(after.lastSequenceNumber,3);
   assert.equal(after.logRevision,before.logRevision+1);
   assert.equal(outcome.removedEventId,events[2].eventId);
   assert.equal(outcome.removedSequenceNumber,3);
   assert.equal(outcome.resultLogRevision,after.logRevision);
   assert.equal(outcome.outcome,'APPLIED');assert.deepEqual(outcome,committed);
   const changes=sse.split('\n\n').filter(frame=>frame.includes('event:event-log-changed'));
   assert.equal(changes.length,1,sse);
   assert.ok(changes[0].includes(events[2].eventId));assert.ok(changes[0].includes('DELETED'));
   const evidence={mode,apiBase:base,proxyPort,browser:await browser.version(),userAgent:await page.evaluate(()=>navigator.userAgent),nativeFetchInvocations:1,incomingPosts:posts.length,warmedClientPort:warm.clientPort,postClientPorts:posts.map(p=>p.clientPort),browserResult,beforeIds:before.events.map(e=>e.id),afterIds:after.events.map(e=>e.id),beforeRevision:before.logRevision,afterRevision:after.logRevision,outcome,committedOutcomeSha256:createHash('sha256').update(JSON.stringify(committed)).digest('hex'),eventLogChangedCount:changes.length};
   fs.writeFileSync(path.join(dir,'after.json'),JSON.stringify(after,null,2));
   fs.writeFileSync(path.join(dir,'receipt.json'),JSON.stringify(outcome,null,2));
   fs.writeFileSync(path.join(dir,'sse.txt'),sse);
   fs.writeFileSync(path.join(dir,'conclusion.json'),JSON.stringify(evidence,null,2));
   await page.screenshot({path:path.join(dir,'native-fetch.png'),fullPage:true});
   results.push(evidence);console.log(JSON.stringify({mode,result:'PASS',nativeFetch:1,incomingPosts:posts.length,revision:[before.logRevision,after.logRevision],removedEventId:outcome.removedEventId,SSE:changes.length}));
  }finally{
   abort.abort();await pump;await page.close();proxy.kill('SIGTERM');await once(proxy,'exit');
   fs.writeFileSync(path.join(dir,'proxy.log'),proxyOut+proxyError);
  }
 }
 fs.writeFileSync(path.join(root,'conclusion.json'),JSON.stringify(results,null,2));
}finally{await browser.close();}
