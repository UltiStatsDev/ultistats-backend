// Own synthetic PostgreSQL + real JAR/HTTPS only. Input contains fixture paths, never human data.
import assert from 'node:assert/strict';
import https from 'node:https';
import {readFileSync,writeFileSync} from 'node:fs';
import {execFileSync,spawn} from 'node:child_process';
import {createHash,randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';
const input=JSON.parse(readFileSync(0,'utf8'));
const {origin,pg,evidence,ca,launcher,playwright,helper,jar}=input;
assert.match(pg,/^obs254-pg-/);
const sql=text=>execFileSync('docker',['exec','-i',pg,'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','obs254','-d','obs254'],{input:text,encoding:'utf8'}).trim();
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
  assert.equal(response.status,expected,`${method} ${path}: ${response.status}/${response.raw.toString().match(/"code":"([A-Z_]+)"/)?.[1]}`);
  const text=response.raw.toString();
  return {...response,data:response.headers['content-type']?.includes('json')&&text?JSON.parse(text):null};
}
const installation=(await call('/api/v1/auth/session')).data.installationId;
let context=(await call('/api/v1/auth/login',{method:'POST',headers:{'X-UltiStats-Installation-Id':installation},body:{login:'obs254-owner',password:input.password??'Synthetic-obs254-start'}})).data;
function capture(value) {
  identity={'X-UltiStats-Installation-Id':value.installationId,'X-UltiStats-Account-Id':value.account.id,'X-UltiStats-Session-Id':value.sessionId};
}
capture(context);
if(context.state==='PASSWORD_CHANGE_REQUIRED') context=(await call('/api/v1/auth/password',{method:'POST',body:{currentPassword:'Synthetic-obs254-start',newPassword:'Synthetic-obs254-permanent'}})).data;
capture(context);
sql('UPDATE matches SET deleted_at=now();');
const match=randomUUID(), other=randomUUID(), teams=[randomUUID(),randomUUID()], players=[[],[]];
sql(`INSERT INTO matches(id,players_per_team) VALUES ('${match}',7),('${other}',7);`);
for(let side=0;side<2;side++) {
  const team=teams[side];
  sql(`INSERT INTO teams(id,name) VALUES ('${team}','Live global name'); INSERT INTO match_teams(match_id,team_id,position,score,team_name) VALUES ('${match}','${team}',${side+1},0,'Snapshot team ${side}');`);
  for(let n=0;n<(side?2:12);n++) {
    const id=randomUUID();players[side].push(id);
    sql(`INSERT INTO match_participants(match_id,participant_id,team_id,kind,first_name,last_name,number) VALUES ('${match}','${id}','${team}','PLAYER','Снимок${n}','ОченьДлиннаяФамилияБезПробеловДляПроверкиКарточки',${n});`);
  }
  for(let n=1;n<=2;n++)sql(`INSERT INTO match_participants(match_id,participant_id,team_id,kind,unknown_slot) VALUES ('${match}','${randomUUID()}','${team}','UNKNOWN',${n});`);
}
sql(`INSERT INTO match_teams(match_id,team_id,position,score,team_name) SELECT '${other}',team_id,position,score,team_name FROM match_teams WHERE match_id='${match}'; INSERT INTO match_participants(match_id,participant_id,team_id,kind,unknown_slot,first_name,last_name,number) SELECT '${other}',participant_id,team_id,kind,unknown_slot,first_name,last_name,number FROM match_participants WHERE match_id='${match}';`);
async function issue(scope,id=match,operationId=randomUUID()) {
  return (await call('/api/v1/obs/source-keys',{method:'POST',body:{scope,operationId,...(id?{matchId:id}:{})},expected:201})).data;
}
const keyOf=key=>key.url.split('#key=')[1];
const read=(key,target=match,expected=200)=>call(`/api/v1/obs/sources/infographics/${target}`,{token:keyOf(key),staff:false,expected});
const operation=randomUUID(), exact=await issue('EXACT_INFOGRAPHICS',match,operation), auto=await issue('AUTO_INFOGRAPHICS',null);
assert.deepEqual(exact,await issue('EXACT_INFOGRAPHICS',match,operation));
assert.deepEqual(exact,(await call(`/api/v1/obs/source-keys/current/${match}?scope=EXACT_INFOGRAPHICS`)).data);
assert.equal((await read(auto,'auto')).data.snapshot,null);
assert.equal((await read(exact)).data.phase.kind,'HIDDEN');
await read(exact,other,403);await read(exact,'auto',403);await read(auto,match,403);
for(const scope of ['EXACT_SCOREBOARD','EXACT_EVENT_LOG','AUTO_SCOREBOARD','AUTO_EVENT_LOG']) {
  const old=await issue(scope,scope.startsWith('AUTO')?null:match);
  await read(old,match,403);await read(old,'auto',403);
  const kind=scope.endsWith('SCOREBOARD')?'scoreboard':'event-log', target=scope.startsWith('AUTO')?'auto':match;
  await call(`/api/v1/obs/sources/${kind}/${target}`,{token:keyOf(old),staff:false});
  await call(`/api/v1/obs/sources/${kind}/${target}`,{token:keyOf(exact),staff:false,expected:403});
}
await call(`/api/v1/obs/sources/infographics/${match}`,{staff:false,expected:401});
await call(`/api/v1/obs/sources/infographics/${match}`,{expected:401});
await call('/api/v1/obs/source-keys',{method:'POST',body:{scope:'AUTO_INFOGRAPHICS',matchId:match,operationId:randomUUID()},expected:400});
await call(`/api/v1/obs/sources/infographics/${match}`,{token:keyOf(exact),staff:false,method:'HEAD',expected:403});
await call('/api/v1/obs/source-keys',{method:'POST',body:{scope:'EXACT_INFOGRAPHICS',matchId:match,operationId:randomUUID()},csrf:false,expected:403});
await call(`/api/v1/obs/previews/infographics/${match}`,{token:keyOf(exact),staff:false,expected:401});
const inventory=(await call('/api/v1/obs/source-keys')).data;
assert.equal(inventory.filter(k=>k.revokedAt==null&&(k.matchId===match||k.matchId===null)).length,6);assert.equal(JSON.stringify(inventory).includes('#key='),false);
const openapi=(await call('/v3/api-docs')).data;
assert.ok(openapi.paths['/api/v1/obs/sources/infographics/{matchId}']);
assert.ok(openapi.paths['/api/v1/obs/previews/infographics/auto']);
assert.ok(openapi.components.schemas.ObsSourceKeyMetadata.properties.scope.enum.includes('AUTO_INFOGRAPHICS'));
writeFileSync(`${evidence}/openapi.json`,JSON.stringify(openapi));
results.push('real issue/current/idempotency/inventory/OpenAPI; old4/new2 cross-kind denial; missing key, HEAD, CSRF and preview bearer denial');
const served=await call('/staff-auth-v1.mjs',{staff:false});
assert.equal(createHash('sha256').update(served.raw).digest('hex'),createHash('sha256').update(readFileSync(helper)).digest('hex'));
const assetProof=[];
for(const path of ['obs-sources/infographics.html','obs-preview/infographics.html','obs/infographics.mjs','obs/infographics-model.mjs','obs/infographics.css','obs/protected-source-client.mjs','obs/staff-preview.mjs','obs/active-links.mjs','obs/active-links-admin.mjs','obs-admin.html','auth/staff-session.mjs']) {
  const source=readFileSync(new URL('../../main/resources/static/'+path,import.meta.url));
  const packaged=execFileSync('unzip',['-p',jar,'BOOT-INF/classes/static/'+path]);
  const delivered=(await call('/'+path,{staff:path.startsWith('obs-preview/')||path==='obs-admin.html'||path.includes('active-links')||path.includes('staff-')})).raw;
  assert.deepEqual(packaged,source,path+' JAR');assert.deepEqual(delivered,source,path+' served');
  assetProof.push({path,sha256:createHash('sha256').update(source).digest('hex'),sourceJarServedEqual:true});
}
const migration=readFileSync(new URL('../../main/resources/db/migration/V21__obs_infographics.sql',import.meta.url));
assert.deepEqual(execFileSync('unzip',['-p',jar,'BOOT-INF/classes/db/migration/V21__obs_infographics.sql']),migration);
writeFileSync(`${evidence}/artifact-bytes.json`,JSON.stringify({jarSHA256:createHash('sha256').update(readFileSync(jar)).digest('hex'),assets:assetProof,migrationSHA256:createHash('sha256').update(migration).digest('hex'),helperSHA256:createHash('sha256').update(served.raw).digest('hex')},null,2));
const {chromium}=await import(pathToFileURL(playwright).href);
const browser=await chromium.launch({executablePath:launcher,headless:true});
try {
  const viewer=await browser.newContext({viewport:{width:1920,height:1080}}), page=await viewer.newPage(), errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  viewer.on('request',request=>{
    if(!request.url().includes('/api/'))return;
    const path=new URL(request.url()).pathname, headers=request.headers();
    assert.equal(headers.cookie,undefined);assert.ok(path.startsWith('/api/v1/obs/sources/infographics/'));
    wire.push({path,bearer:Boolean(headers.authorization),cookie:false});
  });
  const start=()=>sql(`UPDATE matches SET started_at=now(),ended_at=NULL WHERE id='${match}';`);
  start();
  await page.goto(exact.url);
  await page.waitForSelector('.person');
  assert.deepEqual(await page.locator('.team-grid').evaluateAll(nodes=>nodes.map(n=>n.children.length)),[5,2]);
  assert.equal((await read(exact)).data.teams[0].roster.length,12);
  assert.equal(await page.locator('body').innerText().then(t=>t.includes('Неизвестный')),false);
  const bounds=await page.locator('#infographics').boundingBox();
  await page.waitForFunction(()=>document.querySelector('.team-strip p')?.textContent==='6–10 из 12');
  assert.deepEqual(await page.locator('#infographics').boundingBox(),bounds);
  await page.waitForTimeout(250);
  assert.equal(await page.locator('.number').count(),7);
  await page.screenshot({path:`${evidence}/roster-1920.png`});
  await page.reload();await page.waitForFunction(()=>document.querySelector('.team-strip p')?.textContent==='6–10 из 12');
  await page.setViewportSize({width:320,height:180});
  const small=await page.locator('#infographics').boundingBox();
  assert.ok(small.x>=0&&small.y>=0&&small.x+small.width<=320&&small.y+small.height<=180);
  await page.waitForTimeout(250);
  await page.screenshot({path:`${evidence}/roster-320x180.png`});
  await page.emulateMedia({reducedMotion:'reduce'});
  assert.equal(await page.locator('.team-grid').first().evaluate(n=>getComputedStyle(n).animationName),'none');
  await call(`/api/v1/matches/${match}/statistics`,{staff:false,token:keyOf(exact),expected:401});
  await call('/api/v1/obs/stream',{staff:false,token:keyOf(exact),expected:401});
  const pull=(await call(`/api/v1/matches/${match}/events`,{method:'POST',body:{type:'PULL',participantId:players[0][0],occurredAt:new Date().toISOString(),eventId:randomUUID()},expected:201})).data;
  assert.ok(pull.id);
  await page.waitForFunction(()=>document.querySelector('#infographics').hidden);
  assert.equal((await read(exact)).data.phase.kind,'HIDDEN');
  await call(`/api/v1/matches/${match}/events/undo`,{method:'POST'});
  assert.equal((await read(exact)).data.phase.kind,'ROSTER');
  start();await page.waitForSelector('.person');
  sql(`UPDATE matches SET started_at=now()-interval '30 seconds' WHERE id='${match}';`);
  await page.waitForFunction(()=>document.querySelector('#infographics').hidden);
  await page.reload();await page.waitForTimeout(1200);assert.equal(await page.locator('#infographics').isHidden(),true);
  start();await page.goto(auto.url);await page.waitForSelector('.person');
  sql(`UPDATE matches SET started_at=now() WHERE id='${other}';`);
  assert.equal((await read(auto,'auto')).data.snapshot.matchId,other);
  await page.waitForResponse(async response=>response.url().endsWith('/infographics/auto')&&response.status()===200&&(await response.json()).snapshot?.matchId===other);
  sql(`UPDATE matches SET deleted_at=now() WHERE id='${other}';`);
  assert.equal((await read(auto,'auto')).data.snapshot.matchId,match);
  results.push('AUTO real browser selection/switch/deletion fallback');
  results.push('real viewer: five cards/short side, stable outer bounds, reload paging, narrow frame, long names, reduced motion, valid HTTP PULL/undo, finite expiry/reload');
  const staff=await browser.newContext({viewport:{width:1280,height:720}});
  await staff.addCookies([...cookies].map(([name,value])=>({name,value,url:origin,secure:true})));
  const preview=await staff.newPage();
  staff.on('request',request=>{
    const path=new URL(request.url()).pathname;
    if(path.startsWith('/api/v1/obs/previews/')) {
      const headers=request.headers();assert.equal(headers.authorization,undefined);assert.ok(headers['x-ultistats-session-id']);
      wire.push({path,bearer:false,capturedIdentity:true});
    }
  });
  start();await preview.goto(`${origin}/obs-preview/infographics.html?matchId=${match}`);
  await preview.waitForSelector('.person');assert.equal(new URL(preview.url()).hash,'');
  await preview.waitForTimeout(250);
  await preview.screenshot({path:`${evidence}/private-preview.png`});
  await staff.clearCookies();await preview.waitForFunction(()=>document.querySelector('#infographics').hidden);
  const privateReads=()=>wire.filter(r=>r.path.startsWith('/api/v1/obs/previews/')).length;
  const count=privateReads();await preview.waitForTimeout(1300);assert.equal(privateReads(),count);
  await preview.goto('about:blank');
  results.push('genuine frozen staff helper preview: captured identity/no bearer, session loss hides and stops reads, pagehide disposal');
  assert.deepEqual(errors,[]);
} finally {await browser.close();}
// Force a writer between the match read and participant/team reads. The response
// must retain the original database snapshot, rather than mixing two commits.
sql(`UPDATE matches SET ended_at=NULL,started_at=now() WHERE id='${match}';`);
const lock=spawn('docker',['exec','-i',pg,'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','obs254','-d','obs254']);
const locked=new Promise((resolve,reject)=>{
  lock.stdout.on('data',chunk=>{if(chunk.toString().includes('LOCKED'))resolve();});
  lock.on('error',reject);lock.on('exit',code=>{if(code)reject(Error('own lock process failed'));});
});
lock.stdin.write("BEGIN; LOCK TABLE match_participants IN ACCESS EXCLUSIVE MODE;\n\\echo LOCKED\n");
await locked;
const coherent=read(exact);
let waiting=false;
for(let n=0;n<100;n++) {
  waiting=sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'SELECT participant_id,%';")==='1';
  if(waiting)break;
  await new Promise(resolve=>setTimeout(resolve,20));
}
assert.equal(waiting,true,'real API blocked after its match read');
const committed=new Promise((resolve,reject)=>{lock.on('exit',code=>code===0?resolve():reject(Error('own writer failed')));});
lock.stdin.end(`UPDATE matches SET ended_at=now() WHERE id='${match}'; UPDATE match_teams SET team_name='Changed committed team' WHERE match_id='${match}'; UPDATE match_participants SET first_name='Changed committed player' WHERE match_id='${match}' AND kind='PLAYER'; COMMIT;\n`);
await committed;
const old=(await coherent).data, fresh=(await read(exact)).data;
assert.equal(old.status,'IN_PROGRESS');assert.equal(old.teams[0].name,'Snapshot team 0');
assert.equal(old.teams[0].roster.every(p=>p.name.startsWith('Снимок')),true);
assert.equal(fresh.status,'FINISHED');assert.equal(fresh.teams[0].name,'Changed committed team');
assert.equal(fresh.teams[0].roster.every(p=>p.name.startsWith('Changed committed player')),true);
results.push('actual PostgreSQL blocked-reader/concurrent-writer proof: original REPEATABLE_READ roster/team/lifecycle snapshot');
const replacement=await issue('EXACT_INFOGRAPHICS');await read(exact,match,401);
await call(`/api/v1/obs/source-keys/${replacement.key.keyId}`,{method:'DELETE',expected:204});await read(replacement,match,401);
const deletedKey=await issue('EXACT_INFOGRAPHICS');
sql(`UPDATE matches SET deleted_at=now() WHERE id='${match}';`);
await read(deletedKey,match,404);
assert.equal((await read(auto,'auto')).data.snapshot,null);
results.push('rotate/revoke invalidates actual viewer tokens; deleted AUTO target becomes null');
writeFileSync(`${evidence}/http-browser-proof.json`,JSON.stringify({results,wire,normalTLS:true,ownSyntheticOnly:true},null,2));
console.log(results);
