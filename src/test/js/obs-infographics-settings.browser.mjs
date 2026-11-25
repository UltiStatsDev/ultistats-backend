// Own synthetic PostgreSQL + real JAR/HTTPS only. Input contains fixture paths, never human data.
import assert from 'node:assert/strict';
import https from 'node:https';
import {readFileSync,writeFileSync} from 'node:fs';
import {execFileSync,spawn} from 'node:child_process';
import {createHash,randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';
const input=JSON.parse(readFileSync(0,'utf8'));
const {origin,pg,evidence,ca,launcher,playwright,helper,jar}=input;
assert.match(pg,/^obs257-pg-/);
const sql=text=>execFileSync('docker',['exec','-i',pg,'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','obs257','-d','obs257'],{input:text,encoding:'utf8'}).trim();
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
let context=(await call('/api/v1/auth/login',{method:'POST',headers:{'X-UltiStats-Installation-Id':installation},body:{login:'obs257-owner',password:input.password??'Synthetic-obs257-start'}})).data;
function capture(value) {
  identity={'X-UltiStats-Installation-Id':value.installationId,'X-UltiStats-Account-Id':value.account.id,'X-UltiStats-Session-Id':value.sessionId};
}
capture(context);
await call('/api/v1/obs/infographics/settings',{expected:403});
if(context.state==='PASSWORD_CHANGE_REQUIRED') context=(await call('/api/v1/auth/password',{method:'POST',body:{currentPassword:'Synthetic-obs257-start',newPassword:'Synthetic-obs257-permanent'}})).data;
capture(context);

const path='/api/v1/obs/infographics/settings';
const defaults={rosterPageSeconds:5,pointPageSeconds:5,leaderPageSeconds:5,revision:0};
assert.deepEqual((await call(path)).data,defaults);
await call(path,{staff:false,expected:401});
await call(path,{method:'PUT',body:defaults,csrf:false,expected:403});
const saved=(await call(path,{method:'PUT',body:{rosterPageSeconds:2,pointPageSeconds:15,leaderPageSeconds:7}})).data;
assert.equal(saved.revision,1);
assert.deepEqual((await call(path,{method:'PUT',body:saved})).data,saved);
assert.equal(sql('SELECT count(*) FROM obs_infographics_settings_history'),'1');
for(const field of ['rosterPageSeconds','pointPageSeconds','leaderPageSeconds']) {
 for(const value of [1,16,3.5,'5',true,null,2147483648]) await call(path,{method:'PUT',body:{...saved,[field]:value},expected:400});
 const missing={...saved};delete missing[field];await call(path,{method:'PUT',body:missing,expected:400});
}
await call('/api/v1/obs/config',{method:'PUT',body:{maxRows:9,ttlSeconds:12}});
assert.deepEqual((await call(path)).data,saved);
const beforeBlockCSRF=(await call('/api/v1/auth/csrf')).data.token;
sql(`UPDATE auth_accounts SET status='BLOCKED' WHERE id='${context.account.id}';`);
await call(path,{method:'PUT',body:defaults,csrf:false,headers:{'X-CSRF-TOKEN':beforeBlockCSRF},expected:403});
sql(`UPDATE auth_accounts SET status='ACTIVE' WHERE id='${context.account.id}';`);
// Force authority loss while the original settings transaction waits on its ordinary fence.
const fence=spawn('docker',['exec','-i',pg,'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','obs257','-d','obs257']);
const locked=new Promise((resolve,reject)=>{fence.stdout.on('data',chunk=>{if(chunk.toString().includes('LOCKED'))resolve();});fence.on('error',reject);});
fence.stdin.write("BEGIN; SELECT pg_advisory_xact_lock(1598115923,152);\n\\echo LOCKED\n");await locked;
const denied=call(path,{method:'PUT',body:defaults,csrf:false,headers:{'X-CSRF-TOKEN':beforeBlockCSRF},expected:403});
let waiting=false;
for(let n=0;n<100;n++) {waiting=Number(sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event='advisory'"))>0;if(waiting)break;await new Promise(r=>setTimeout(r,20));}
assert.equal(waiting,true,'settings mutation waits inside original authority fence');
const released=new Promise((resolve,reject)=>fence.on('exit',code=>code===0?resolve():reject(Error('fence writer'))));
fence.stdin.end(`UPDATE auth_accounts SET status='BLOCKED' WHERE id='${context.account.id}'; COMMIT;\n`);await released;await denied;
sql(`UPDATE auth_accounts SET status='ACTIVE' WHERE id='${context.account.id}';`);
assert.deepEqual((await call(path)).data,saved,'capture-loss rolls back settings and history');
results.push('PG durable defaults, independent roundtrip, idempotent revision/history, strict fields, old config isolation, auth/CSRF/blocked account');
// Synthetic epochs exercise actual API snapshots across interval edits and reconnects.
const match=randomUUID(),teams=[randomUUID(),randomUUID()],players=[[],[]];
sql(`INSERT INTO matches(id,players_per_team,started_at) VALUES ('${match}',7,now());`);
for(let t=0;t<2;t++) {
 sql(`INSERT INTO teams(id,name) VALUES ('${teams[t]}','Team ${t}'); INSERT INTO match_teams(match_id,team_id,position,score,team_name) VALUES ('${match}','${teams[t]}',${t+1},0,'Snapshot ${t}');`);
 for(let n=0;n<12;n++) {
  const player=randomUUID();players[t].push(player);
  sql(`INSERT INTO match_participants(match_id,participant_id,team_id,kind,first_name,last_name,number) VALUES ('${match}','${player}','${teams[t]}','PLAYER','Player','${n}',${n});`);
 }
}
const key=(await call('/api/v1/obs/source-keys',{method:'POST',body:{scope:'EXACT_INFOGRAPHICS',matchId:match,operationId:randomUUID()},expected:201})).data;
const token=key.url.split('#key=')[1];
await call(path,{staff:false,token,expected:401});
const preview=`/api/v1/obs/previews/infographics/${match}`;
let snapshot=(await call(preview)).data;
assert.equal(snapshot.phase.kind,'ROSTER');assert.equal(snapshot.phase.pageSeconds,2);
const start=snapshot.phase.startedAt;
const points=(await call(`/api/v1/matches/${match}/point-lineups`)).data;
await call(`/api/v1/matches/${match}/point-lineups/${points.points[0].pointId}`,{method:'PUT',headers:{'X-UltiStats-Point-Lineups':'1'},body:{expectedLogRevision:points.logRevision,teams:teams.map((teamId,t)=>({teamId,entries:players[t].slice(0,7).map(participantId=>({kind:'PLAYER',participantId}))}))}});
const confirmed=(await call(preview)).data;
assert.equal(Date.parse(confirmed.phase.lineupStartedAt)-Date.parse(start),6000);
const changed=(await call(path,{method:'PUT',body:{rosterPageSeconds:15,pointPageSeconds:3,leaderPageSeconds:11}})).data;
const after=(await call(preview)).data;
assert.equal(after.phase.pageSeconds,2);assert.equal(after.phase.startedAt,start);
assert.equal(after.phase.lineupStartedAt,confirmed.phase.lineupStartedAt);
assert.equal(after.phase.lineupPageSeconds,3);assert.equal(after.settings.revision,changed.revision);
assert.notEqual(after.presentationRevision,confirmed.presentationRevision);
// As-of timestamps are synthetic fixture data only: make phase active before another edit.
sql(`UPDATE matches SET started_at=now()-interval '40 seconds' WHERE id='${match}'; UPDATE match_point_lineups SET confirmed_at=now()-interval '20 seconds' WHERE match_id='${match}';`);
const active=(await call(preview)).data;
assert.equal(active.phase.kind,'LINEUP');assert.equal(active.phase.pageSeconds,5,'pre-settings phase honestly keeps original default');
await call(path,{method:'PUT',body:{rosterPageSeconds:4,pointPageSeconds:9,leaderPageSeconds:6}});
const reconnect=(await call(preview)).data;
assert.equal(reconnect.phase.pageSeconds,5);assert.equal(reconnect.phase.startedAt,active.phase.startedAt);
assert.equal(reconnect.phase.lineupStartedAt,active.phase.lineupStartedAt);
sql(`UPDATE match_point_lineups SET confirmed_at=now() WHERE match_id='${match}';`);
const nextPhase=(await call(preview)).data;
assert.equal(nextPhase.phase.pageSeconds,9,'new phase captures latest point interval');
results.push('real phase as-of intervals: unchanged roster minimum deadline, future lineup captured independently, reload active phase stable, current revision changes');
const api=(await call('/v3/api-docs')).data;
assert.deepEqual(api.paths[path].put.security,[{staffSession:[]}]);
const schema=api.components.schemas.ObsInfographicsSettingsRequest;
assert.deepEqual([...schema.required].sort(),['leaderPageSeconds','pointPageSeconds','rosterPageSeconds']);
for(const field of schema.required) {assert.equal(schema.properties[field].type,'integer');assert.equal(schema.properties[field].minimum,2);assert.equal(schema.properties[field].maximum,15);}
writeFileSync(`${evidence}/openapi.json`,JSON.stringify(api));
const assets=[];
for(const name of ['obs-admin.html','obs/admin.mjs','obs/infographics-settings-admin.mjs','obs/infographics-model.mjs','auth/staff-session.mjs']) {
 const source=readFileSync(new URL('../../main/resources/static/'+name,import.meta.url));
 assert.deepEqual(execFileSync('unzip',['-p',jar,'BOOT-INF/classes/static/'+name]),source);
 assert.deepEqual((await call('/'+name)).raw,source);
 assets.push({path:name,sha256:createHash('sha256').update(source).digest('hex'),sourceJarServedEqual:true});
}
const migration='V23__obs_infographics_settings.sql';
const bytes=readFileSync(new URL('../../main/resources/db/migration/'+migration,import.meta.url));
assert.deepEqual(execFileSync('unzip',['-p',jar,'BOOT-INF/classes/db/migration/'+migration]),bytes);
assert.equal(sql("SELECT success FROM flyway_schema_history WHERE version='23'"),'t');
assert.equal(createHash('sha256').update((await call('/staff-auth-v1.mjs')).raw).digest('hex'),createHash('sha256').update(readFileSync(helper)).digest('hex'));
writeFileSync(`${evidence}/artifact-bytes.json`,JSON.stringify({jarSHA256:createHash('sha256').update(readFileSync(jar)).digest('hex'),assets,migration:{name:migration,sha256:createHash('sha256').update(bytes).digest('hex'),sourceJarEqual:true,flywayApplied:true}},null,2));
const {chromium}=await import(pathToFileURL(playwright).href);
const browser=await chromium.launch({executablePath:launcher,headless:true});
try {
 const staff=await browser.newContext({viewport:{width:1200,height:900}}),page=await staff.newPage(),errors=[];
 await staff.addCookies([...cookies].map(([name,value])=>({name,value,url:origin,secure:true})));
 page.on('pageerror',e=>errors.push(e.message));
 staff.on('request',request=>{if(new URL(request.url()).pathname===path){assert.equal(request.headers().authorization,undefined);assert.ok(request.headers()['x-ultistats-session-id']);wire.push({method:request.method(),capturedIdentity:true,bearer:false});}});
 await page.goto(origin+'/obs-admin.html');await page.waitForFunction(()=>!document.querySelector('#infographics-settings fieldset').disabled);
 for(const [name,value] of Object.entries({rosterPageSeconds:2,pointPageSeconds:15,leaderPageSeconds:10})) await page.fill(`[name=${name}]`,String(value));
 await page.locator('#infographics-settings button').click();await page.waitForFunction(()=>document.querySelector('#infographics-status').textContent.includes('Сохранено'));
 assert.equal((await call(path)).data.leaderPageSeconds,10);
 await page.reload();await page.waitForFunction(()=>document.querySelector('[name=leaderPageSeconds]').value==='10');
 assert.equal(await page.inputValue('[name=rosterPageSeconds]'),'2');assert.equal(await page.inputValue('[name=pointPageSeconds]'),'15');
 await page.screenshot({path:`${evidence}/private-settings.png`});
 await staff.clearCookies();await page.locator('#infographics-settings button').click();
 await page.waitForFunction(()=>document.querySelector('#infographics-settings fieldset').disabled && document.querySelector('[name=pointPageSeconds]').value==='');
 assert.deepEqual(errors,[]);
 results.push('normal TLS genuine helper staff form: three controls, save, reload, captured identity/no bearer, session loss clears and disables');
} finally {await browser.close();}
writeFileSync(`${evidence}/http-browser-proof.json`,JSON.stringify({results,wire,ownSyntheticOnly:true,normalTLS:true},null,2));console.log(results);
