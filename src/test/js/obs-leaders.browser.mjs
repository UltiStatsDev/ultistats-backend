// Own synthetic PostgreSQL + real JAR/HTTPS only. Input contains fixture paths, never human data.
import assert from 'node:assert/strict';
import https from 'node:https';
import {readFileSync,writeFileSync} from 'node:fs';
import {execFileSync,spawn} from 'node:child_process';
import {createHash,randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';
const input=JSON.parse(readFileSync(0,'utf8'));
const {origin,pg,evidence,ca,launcher,playwright,helper,jar}=input;
assert.match(pg,/^obs256-pg-/);
const sql=text=>execFileSync('docker',['exec','-i',pg,'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','obs256','-d','obs256'],{input:text,encoding:'utf8'}).trim();
const cookies=new Map(), results=[], wire=[];
let identity={};
async function call(path,{method='GET',body,token,staff=true,csrf=true,expected=200,headers={}}={}) {
  const csrfToken=csrf && method!=='GET' && method!=='HEAD' ? (await call('/api/v1/auth/csrf')).data.token : null;
  const requestHeaders={...headers};
  if(body!==undefined)requestHeaders['Content-Length']=Buffer.byteLength(JSON.stringify(body));
  if(staff) {
    requestHeaders.Cookie=[...cookies].map(([k,v])=>`${k}=${v}`).join('; ');
    Object.assign(requestHeaders,identity);
  }
  if(token)requestHeaders.Authorization=`Bearer ${token}`;
  if(body!==undefined)requestHeaders['Content-Type']='application/json';
  if(method!=='GET'&&method!=='HEAD') {
    requestHeaders.Origin=origin;
    if(csrfToken)requestHeaders['X-CSRF-TOKEN']=csrfToken;
  }
  const response=await new Promise((resolve,reject)=>{
    const request=https.request(new URL(path,origin),{method,headers:requestHeaders,ca:readFileSync(ca)},response=>{
      const chunks=[];response.on('data',c=>chunks.push(c));response.on('end',()=>resolve({status:response.statusCode,headers:response.headers,raw:Buffer.concat(chunks)}));
    });
    request.on('error',reject);request.end(body===undefined?undefined:JSON.stringify(body));
  });
  for(const entry of response.headers['set-cookie']??[]) {
    const [pair]=entry.split(';'), split=pair.indexOf('=');cookies.set(pair.slice(0,split),pair.slice(split+1));
  }
  assert.equal(response.status,expected,`${method} ${path}: ${response.status}/${response.raw.toString().match(/"code":"([A-Z_]+)"/)?.[1]}${path.includes('/point-lineups') ? response.raw.toString().slice(0,400) : ''}`);
  const text=response.raw.toString();
  return {...response,data:response.headers['content-type']?.includes('json')&&text?JSON.parse(text):null};
}
const installation=(await call('/api/v1/auth/session')).data.installationId;
let context=(await call('/api/v1/auth/login',{method:'POST',headers:{'X-UltiStats-Installation-Id':installation},body:{login:'obs256-owner',password:input.password??'Synthetic-obs256-start'}})).data;
function capture(value) {
  identity={'X-UltiStats-Installation-Id':value.installationId,'X-UltiStats-Account-Id':value.account.id,'X-UltiStats-Session-Id':value.sessionId};
}
capture(context);
await call('/api/v1/obs/infographics/leaders/auto',{expected:403});
if(context.state==='PASSWORD_CHANGE_REQUIRED') context=(await call('/api/v1/auth/password',{method:'POST',body:{currentPassword:'Synthetic-obs256-start',newPassword:'Synthetic-obs256-permanent'}})).data;
capture(context);

const match=randomUUID(), teams=[randomUUID(),randomUUID()], players=[[randomUUID(),randomUUID()],[randomUUID(),randomUUID()]], slot=randomUUID();
const path=`/api/v1/obs/infographics/leaders/${match}`;
await call('/obs-leaders.html',{staff:false,expected:401});
await call(path,{staff:false,expected:401});
assert.equal((await call('/api/v1/obs/infographics/leaders/auto')).data.snapshot,null);
sql(`INSERT INTO matches(id,players_per_team) VALUES ('${match}',7);`);
for(let side=0;side<2;side++) {
 sql(`INSERT INTO teams(id,name) VALUES ('${teams[side]}','Live global'); INSERT INTO match_teams(match_id,team_id,position,score,team_name) VALUES ('${match}','${teams[side]}',${side+1},0,'Snapshot ${side}');`);
 for(let n=0;n<2;n++)sql(`INSERT INTO match_participants(match_id,participant_id,team_id,kind,first_name,last_name,number) VALUES ('${match}','${players[side][n]}','${teams[side]}','PLAYER','Snapshot${side}${n}','Player',${n+1});`);
 for(let n=1;n<=2;n++)sql(`INSERT INTO match_participants(match_id,participant_id,team_id,kind,unknown_slot) VALUES ('${match}','${randomUUID()}','${teams[side]}','UNKNOWN',${n});`);
}
sql(`UPDATE auth_accounts SET role='ORGANIZER' WHERE id='${context.account.id}';`);
await call(path);
sql(`UPDATE auth_accounts SET role='ADMIN',status='BLOCKED' WHERE id='${context.account.id}';`);
await call(path,{expected:403});
sql(`UPDATE auth_accounts SET status='ACTIVE' WHERE id='${context.account.id}';`);
const emptyResponse=await call(path);assert.equal(emptyResponse.headers['cache-control'],'no-store');
const empty=emptyResponse.data;
assert.ok(empty.teams.every(t=>t.attack.length===0&&t.defense.length===0));
assert.deepEqual(empty,(await call(path)).data,'poll does not create revision noise');
await call('/api/v1/obs/infographics/leaders/1',{expected:400});
await call(path,{method:'POST',body:{},expected:403});
const exact=(await call('/api/v1/obs/source-keys',{method:'POST',body:{scope:'EXACT_INFOGRAPHICS',matchId:match,operationId:randomUUID()},expected:201})).data;
await call(path,{staff:false,token:exact.url.split('#key=')[1],expected:401});
const openapi=(await call('/v3/api-docs')).data;
assert.deepEqual(openapi.paths['/api/v1/obs/infographics/leaders/{matchId}'].get.security,[{staffSession:[]}]);
writeFileSync(`${evidence}/openapi.json`,JSON.stringify(openapi));
sql(`UPDATE matches SET started_at=now()-interval '60 seconds' WHERE id='${match}';`);
assert.equal((await call('/api/v1/obs/infographics/leaders/auto')).data.snapshot.matchId,match);
const preConfirm=(await call(path)).data.inputRevision;
const lineups=`/api/v1/matches/${match}/point-lineups`, headers={'X-UltiStats-Point-Lineups':'1'};
const points=(await call(lineups)).data, point=points.points[0];
await call(`${lineups}/${point.pointId}`,{method:'PUT',headers,body:{expectedLogRevision:points.logRevision,teams:teams.map((teamId,i)=>({teamId,entries:i?[{kind:'PLAYER',participantId:players[1][0]}]:[{kind:'TEMPORARY',slotId:slot,number:1},{kind:'PLAYER',participantId:players[0][0]}]}))}});
assert.notEqual((await call(path)).data.inputRevision,preConfirm,'confirmation/identity metadata enters revision even before counters');
let eventTime=Date.now()-30000;
async function event(type,from,to) {
 eventTime+=1000;
 return (await call(`/api/v1/matches/${match}/events`,{method:'POST',headers,expected:201,body:{type,eventId:randomUUID(),occurredAt:new Date(eventTime).toISOString(),...(to?{fromParticipantId:from,toParticipantId:to}:{participantId:from})}})).data;
}
await event('PULL',players[1][0]);await event('PICKUP',slot);
const goal=await event('GOAL',slot,players[0][0]);
let before=(await call(path)).data;
assert.deepEqual(before.teams[0].attack.map(p=>p.participantId),[players[0][0]]);
assert.equal(before.teams[0].attack[0].ratingTenths,15);
const identify=`${lineups}/${point.pointId}/slots/${slot}`;
await call(identify,{method:'PATCH',headers,body:{participantId:players[0][1],expectedLineupRevision:1,expectedClosingEventId:goal.id}});
const identified=(await call(path)).data;
assert.notEqual(before.inputRevision,identified.inputRevision);
assert.deepEqual(identified.teams[0].attack.map(p=>p.participantId),[...players[0]].sort());
assert.ok(identified.teams[0].attack.every(p=>p.ratingTenths===15));
const canonical=(await call(`/api/v1/matches/${match}/statistics`)).data;
assert.equal(canonical.teams[0].participants.find(p=>p.participantId===players[0][1]).attack.assists,1);
await call(identify,{method:'PATCH',headers,body:{participantId:null,expectedLineupRevision:2,expectedClosingEventId:goal.id}});
assert.deepEqual((await call(path)).data.teams[0].attack.map(p=>p.participantId),[players[0][0]]);
await call(`/api/v1/matches/${match}/events/undo`,{method:'POST',headers});
assert.equal((await call(path)).data.teams[0].attack.length,0);
await event('CALLAHAN',slot,players[1][0]);
const defended=(await call(path)).data;
assert.equal(defended.teams[1].attack.length,0);assert.equal(defended.teams[1].defense[0].ratingTenths,75);
results.push('canonical real HTTP: PLANNED zero, AUTO selection, unknown excluded, identify same-team fold/all ties, identity undo/event undo, Callahan defense only, stable revision');
const assets=[];
for(const name of ['obs-leaders.html','obs/leaders.mjs','obs/leaders.css','obs/staff-reader.mjs','obs-admin.html','auth/staff-session.mjs']) {
 const source=readFileSync(new URL('../../main/resources/static/'+name,import.meta.url));
 assert.deepEqual(execFileSync('unzip',['-p',jar,'BOOT-INF/classes/static/'+name]),source);
 const served=(await call('/'+name)).raw;assert.deepEqual(served,source);
 assets.push({path:name,sha256:createHash('sha256').update(source).digest('hex'),sourceJarServedEqual:true});
}
assert.equal(createHash('sha256').update((await call('/staff-auth-v1.mjs')).raw).digest('hex'),createHash('sha256').update(readFileSync(helper)).digest('hex'));
writeFileSync(`${evidence}/artifact-bytes.json`,JSON.stringify({jarSHA256:createHash('sha256').update(readFileSync(jar)).digest('hex'),assets},null,2));
const {chromium}=await import(pathToFileURL(playwright).href);
const browser=await chromium.launch({executablePath:launcher,headless:true});
try {
 const staff=await browser.newContext({viewport:{width:1200,height:800}}), page=await staff.newPage(), errors=[];
 await staff.addCookies([...cookies].map(([name,value])=>({name,value,url:origin,secure:true})));
 page.on('pageerror',e=>errors.push(e.message));
 staff.on('request',request=>{const p=new URL(request.url()).pathname;if(p.startsWith('/api/v1/obs/infographics/leaders/')) {
  assert.equal(request.headers().authorization,undefined);assert.ok(request.headers()['x-ultistats-session-id']);wire.push({path:p,capturedIdentity:true,bearer:false});
 }});
 await page.goto(origin+'/obs-leaders.html');await page.waitForSelector('[data-participant-id]');
 assert.match(await page.locator('#leaders').innerText(),/Snapshot 1/);
 await page.selectOption('[name=mode]','exact');assert.equal(await page.locator('[data-participant-id]').count(),0);
 await page.fill('[name=matchId]',match);await page.click('button[type=submit]');await page.waitForSelector('[data-participant-id]');
 await page.screenshot({path:`${evidence}/private-leaders.png`});
 await staff.clearCookies();await page.waitForFunction(()=>document.querySelectorAll('[data-participant-id]').length===0);
 const count=wire.length;await page.waitForTimeout(1300);assert.equal(wire.length,count);assert.deepEqual(errors,[]);
 results.push('normal TLS genuine frozen helper page: exact/AUTO, snapshot names/rating, binding edit clears, captured identity/no bearer, session loss clears/stops');
} finally {await browser.close();}
// Hold the roster read after match lookup, commit changes, and verify original RR.
const lock=spawn('docker',['exec','-i',pg,'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','obs256','-d','obs256']);
const locked=new Promise((resolve,reject)=>{lock.stdout.on('data',c=>{if(c.toString().includes('LOCKED'))resolve()});lock.on('error',reject)});
lock.stdin.write("BEGIN; LOCK TABLE match_participants IN ACCESS EXCLUSIVE MODE;\n\\echo LOCKED\n");await locked;
const pending=call(path);let waiting=false;
for(let n=0;n<100;n++) {waiting=sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%match_participants%';")==='1';if(waiting)break;await new Promise(r=>setTimeout(r,20));}
assert.equal(waiting,true);
const committed=new Promise((resolve,reject)=>lock.on('exit',c=>c===0?resolve():reject(Error('writer'))));
lock.stdin.end(`UPDATE matches SET ended_at=now() WHERE id='${match}'; UPDATE match_teams SET team_name='Changed committed' WHERE match_id='${match}'; UPDATE match_participants SET first_name='Changed' WHERE match_id='${match}' AND kind='PLAYER'; COMMIT;\n`);await committed;
const old=(await pending).data,fresh=(await call(path)).data;
assert.equal(old.status,'IN_PROGRESS');assert.equal(old.teams[1].name,'Snapshot 1');assert.match(old.teams[1].defense[0].name,/^Snapshot/);
assert.equal(fresh.status,'FINISHED');assert.equal(fresh.teams[1].name,'Changed committed');assert.match(fresh.teams[1].defense[0].name,/^Changed/);assert.notEqual(old.inputRevision,fresh.inputRevision);
sql(`UPDATE matches SET deleted_at=now() WHERE id='${match}';`);await call(path,{expected:404});assert.equal((await call('/api/v1/obs/infographics/leaders/auto')).data.snapshot,null);
results.push('actual PostgreSQL blocked-reader/concurrent-writer original RR coherent status/team/PLAYER/counters and revision; deletion empty AUTO');
writeFileSync(`${evidence}/http-browser-proof.json`,JSON.stringify({results,wire,ownSyntheticOnly:true,normalTLS:true},null,2));console.log(results);
