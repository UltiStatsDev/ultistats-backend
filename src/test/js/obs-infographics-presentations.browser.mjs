// Own synthetic PostgreSQL + real JAR/HTTPS only. Input contains fixture paths, never human data.
import assert from 'node:assert/strict';
import https from 'node:https';
import {readFileSync,writeFileSync} from 'node:fs';
import {execFileSync,spawn} from 'node:child_process';
import {createHash,randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';
const input=JSON.parse(readFileSync(0,'utf8'));
const {origin,pg,evidence,ca,launcher,playwright,helper,jar}=input;
assert.match(pg,/^obs258-pg-/);
const sql=text=>execFileSync('docker',['exec','-i',pg,'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','obs258','-d','obs258'],{input:text,encoding:'utf8'}).trim();
const cookies=new Map(), results=[], wire=[];
let identity={};
async function call(path,{method='GET',body,token,staff=true,csrf=true,expected=200,headers={}}={}) {
  const csrfToken=csrf && method!=='GET' && method!=='HEAD' ? (await call('/api/v1/auth/csrf')).data.token : null;
  const requestHeaders={};
  if(body!==undefined)requestHeaders['Content-Length']=Buffer.byteLength(JSON.stringify(body));
  if(staff) {
    requestHeaders.Cookie=[...cookies].map(([k,v])=>`${k}=${v}`).join('; ');
    Object.assign(requestHeaders,identity);
  }
  Object.assign(requestHeaders,headers);
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
  assert.ok([expected].flat().includes(response.status),`${method} ${path}: ${response.status}/${response.raw.toString().match(/"code":"([A-Z_]+)"/)?.[1]}${path.includes('/point-lineups') ? response.raw.toString().slice(0,400) : ''}`);
  const text=response.raw.toString();
  return {...response,data:response.headers['content-type']?.includes('json')&&text?JSON.parse(text):null};
}
const installation=(await call('/api/v1/auth/session')).data.installationId;
let context=(await call('/api/v1/auth/login',{method:'POST',headers:{'X-UltiStats-Installation-Id':installation},body:{login:'obs258-owner',password:input.password??'Synthetic-obs258-start'}})).data;
function capture(value) {
  identity={'X-UltiStats-Installation-Id':value.installationId,'X-UltiStats-Account-Id':value.account.id,'X-UltiStats-Session-Id':value.sessionId};
}
capture(context);
await call('/api/v1/obs/infographics/leaders/auto',{expected:403});
if(context.state==='PASSWORD_CHANGE_REQUIRED') context=(await call('/api/v1/auth/password',{method:'POST',body:{currentPassword:'Synthetic-obs258-start',newPassword:'Synthetic-obs258-permanent'}})).data;
capture(context);

const match=randomUUID(), teams=[randomUUID(),randomUUID()], players=[[randomUUID(),randomUUID()],[randomUUID(),randomUUID()]], slot=randomUUID();
const leaders=`/api/v1/obs/infographics/leaders/${match}`;
await call('/obs-leaders.html',{staff:false,expected:401});
await call(leaders,{staff:false,expected:401});
assert.equal((await call('/api/v1/obs/infographics/leaders/auto')).data.snapshot,null);
sql(`INSERT INTO matches(id,players_per_team) VALUES ('${match}',7);`);
for(let side=0;side<2;side++) {
 sql(`INSERT INTO teams(id,name) VALUES ('${teams[side]}','Live global'); INSERT INTO match_teams(match_id,team_id,position,score,team_name) VALUES ('${match}','${teams[side]}',${side+1},0,'Snapshot ${side}');`);
 for(let n=0;n<2;n++)sql(`INSERT INTO match_participants(match_id,participant_id,team_id,kind,first_name,last_name,number) VALUES ('${match}','${players[side][n]}','${teams[side]}','PLAYER','Snapshot${side}${n}','ОченьДлиннаяФамилияДляПроверкиУзкойПанелиИнфографики',${n+1});`);
 for(let n=1;n<=2;n++)sql(`INSERT INTO match_participants(match_id,participant_id,team_id,kind,unknown_slot) VALUES ('${match}','${randomUUID()}','${teams[side]}','UNKNOWN',${n});`);
}

const presentations = '/api/v1/obs/infographics/presentations';
const sources = '/api/v1/obs/sources/infographics';
const settings = '/api/v1/obs/infographics/settings';
const lineups = `/api/v1/matches/${match}/point-lineups`, headers = {'X-UltiStats-Point-Lineups':'1'};
const sourceKey = async (scope, matchId) => (await call('/api/v1/obs/source-keys',{
  method:'POST',body:{scope,matchId,operationId:randomUUID()},expected:201,
})).data.url;
const exactUrl = await sourceKey('EXACT_INFOGRAPHICS',match);
const autoUrl = await sourceKey('AUTO_INFOGRAPHICS',null);
const view = async (binding = 'EXACT') => {
  const isAuto = binding === 'AUTO';
  const url = isAuto ? autoUrl : exactUrl;
  const value = (await call(`${sources}/${isAuto?'auto':match}`,{staff:false,token:new URL(url).hash.slice(5)})).data;
  return isAuto ? value.snapshot : value;
};
const captureInput = async (binding = 'EXACT') => {
  const value = (await call(binding === 'AUTO' ? '/api/v1/obs/infographics/leaders/auto' : leaders)).data;
  const snapshot = binding === 'AUTO' ? value.snapshot : value;
  const {canShow,expectedInputRevision,expectedSettingsRevision,expectedGeneration} = snapshot.presentation;
  assert.equal(canShow,true);
  return {binding,expectedMatchId:snapshot.matchId,operationId:randomUUID(),expectedInputRevision,expectedSettingsRevision,expectedGeneration};
};
const show = (body, expected=200) => call(presentations,{method:'POST',body,expected});
const hide = (generation,expected=204) => call(`${presentations}/${generation}`,{method:'DELETE',expected});
sql(`UPDATE match_participants SET number=NULL WHERE participant_id='${players[1][0]}';`);
sql(`UPDATE matches SET started_at=now()-interval '60 seconds' WHERE id='${match}';`);
const point = (await call(lineups)).data.points[0];
await call(`${lineups}/${point.pointId}`,{method:'PUT',headers,body:{expectedLogRevision:0,
  teams:teams.map((teamId,i)=>({teamId,entries:i?[{kind:'PLAYER',participantId:players[1][0]}]:
    [{kind:'TEMPORARY',slotId:slot,number:1},{kind:'PLAYER',participantId:players[0][0]}]}))}});
let eventTime = Date.now()-30000;
async function event(type,from,to) {
  eventTime += 1000;
  return (await call(`/api/v1/matches/${match}/events`,{method:'POST',headers,expected:201,body:{
    type,eventId:randomUUID(),occurredAt:new Date(eventTime).toISOString(),
    ...(to?{fromParticipantId:from,toParticipantId:to}:{participantId:from}),
  }})).data;
}
await event('PULL',players[1][0]);
await event('PICKUP',slot);
await event('BLOCK',slot,players[1][0]);
await event('PICKUP',players[1][0]);
await event('INCOMPLETE_PASS',players[1][0]);
await event('PICKUP',slot);
const goal = await event('GOAL',slot,players[0][0]);

let request = await captureInput();
assert.equal(request.expectedGeneration,null);
await show({...request,expectedSettingsRevision:null},400);
await call(presentations,{method:'POST',body:request,staff:false,csrf:false,token:new URL(exactUrl).hash.slice(5),expected:401});
await call(presentations,{method:'POST',body:request,csrf:false,expected:403});
await call(presentations,{method:'POST',body:request,headers:{'X-UltiStats-Session-Id':randomUUID()},expected:403});
assert.equal(sql('SELECT count(*) FROM obs_infographics_presentations'),'0');

const first = (await show(request)).data;
assert.deepEqual((await show(request)).data,first);
assert.equal((await show({...request,expectedSettingsRevision:request.expectedSettingsRevision+1},409)).data.code,
  'OBS_PRESENTATION_OPERATION_CONFLICT');
assert.equal(Date.parse(first.expiresAt)-Date.parse(first.startedAt),2*first.pageSeconds*1000);
const otherRequest = await captureInput();
const race = await Promise.all([show(otherRequest,[200,409]),show({...otherRequest,operationId:randomUUID()},[200,409])]);
assert.deepEqual(race.map(r=>r.status).sort(),[200,409]);
const latest = race.find(r=>r.status===200).data;
await hide(first.generation,409);
assert.equal((await view()).leaderPresentation.generation,latest.generation);
await hide(latest.generation);
assert.deepEqual((await show(request)).data,first);
assert.equal((await view()).leaderPresentation,null);
assert.equal(sql('SELECT count(*) FROM obs_infographics_presentations'),'2');

await show(await captureInput());
const staleSettings = await captureInput();
await call(settings,{method:'PUT',body:{rosterPageSeconds:5,pointPageSeconds:5,leaderPageSeconds:4}});
assert.equal((await show(staleSettings,409)).data.code,'OBS_PRESENTATION_INPUT_STALE');
assert.equal((await view()).leaderPresentation,null);

request = await captureInput();
const beforeIdentification = (await show(request)).data;
const identify = `${lineups}/${point.pointId}/slots/${slot}`;
await call(identify,{method:'PATCH',headers,body:{participantId:players[0][1],expectedLineupRevision:1,expectedClosingEventId:goal.id}});
assert.equal((await view()).leaderPresentation,null);
await call(identify,{method:'PATCH',headers,body:{participantId:null,expectedLineupRevision:2,expectedClosingEventId:goal.id}});
assert.equal((await view()).leaderPresentation,null);
assert.notEqual((await captureInput()).expectedInputRevision,request.expectedInputRevision);
assert.deepEqual((await show(request)).data,beforeIdentification);
await show(await captureInput());
await call(`/api/v1/matches/${match}/events/undo`,{method:'POST',headers});
assert.equal((await view()).leaderPresentation,null);
await event('GOAL',slot,players[0][0]);

request = await captureInput('AUTO');
const auto = (await show(request)).data;
assert.equal((await view('AUTO')).leaderPresentation.generation,auto.generation);
assert.equal((await view()).leaderPresentation,null);
const pendingAuto = await captureInput('AUTO'), other = randomUUID();
sql(`INSERT INTO matches(id,players_per_team,started_at) VALUES ('${other}',7,now());
INSERT INTO match_teams(match_id,team_id,position,score,team_name)
SELECT '${other}',team_id,position,score,team_name FROM match_teams WHERE match_id='${match}';`);
assert.equal((await view('AUTO')).matchId,other);
assert.equal((await show(pendingAuto,409)).data.code,'OBS_PRESENTATION_INPUT_STALE');
await call(`/api/v1/matches/${other}`,{method:'DELETE',expected:204});
assert.equal((await view('AUTO')).matchId,match);
assert.equal((await view('AUTO')).leaderPresentation,null);
assert.equal((await show(pendingAuto,409)).data.code,'OBS_PRESENTATION_INPUT_STALE');
assert.deepEqual((await show(request)).data,auto);
results.push('PG HTTP: CSRF/session/scope, durable receipts, concurrent CAS, no revival, E invalidation, identify/unidentify ABA, undo, AUTO switch/delete ABA');

async function blockedCommand(command, lockSql, waitEvent, commitSql, expected) {
  const lock = spawn('docker',['exec','-i',pg,'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','obs258','-d','obs258']);
  try {
    const locked = new Promise((resolve,reject)=>{lock.stdout.on('data',c=>{if(c.toString().includes('LOCKED'))resolve();});lock.on('error',reject);});
    lock.stdin.write(`BEGIN; ${lockSql}\n\\echo LOCKED\n`);
    await locked;
    const pending = show(command,expected);
    let waiting = false;
    for (let n=0;n<100;n++) {
      waiting = Number(sql(`SELECT count(*) FROM pg_stat_activity WHERE ${waitEvent}`)) > 0;
      if (waiting) break;
      await new Promise(resolve=>setTimeout(resolve,20));
    }
    assert.equal(waiting,true);
    const released = new Promise((resolve,reject)=>lock.on('exit',code=>code===0?resolve():reject(Error('fixture lock writer'))));
    lock.stdin.end(`${commitSql} COMMIT;\n`);
    await released;
    return (await pending).data.code;
  } finally {if (!lock.stdin.destroyed) lock.stdin.end();}
}
const count = sql('SELECT count(*) FROM obs_infographics_presentations');
assert.equal(await blockedCommand(await captureInput('AUTO'),`SELECT id FROM matches WHERE id='${match}' FOR UPDATE;`,
  "wait_event_type='Lock' AND query LIKE '%matches%'",
  `UPDATE matches SET event_log_revision=event_log_revision+1 WHERE id='${match}';`,409),'OBS_PRESENTATION_INPUT_STALE');
assert.equal(await blockedCommand(await captureInput(),'SELECT pg_advisory_xact_lock(1598115923,152);',"wait_event='advisory'",
  `UPDATE auth_accounts SET status='BLOCKED' WHERE id='${context.account.id}';`,403),'AUTH_ACCOUNT_BLOCKED');
sql(`UPDATE auth_accounts SET status='ACTIVE' WHERE id='${context.account.id}';`);
assert.equal(sql('SELECT count(*) FROM obs_infographics_presentations'),count);
results.push('PG blocked AUTO locks expected match before JPA selection; authority loss on original advisory fence rolls back without command');

const api = (await call('/v3/api-docs')).data;
assert.deepEqual(api.paths[presentations].post.security,[{staffSession:[]}]);
assert.equal(api.paths[presentations].post.responses['200'].content['*/*'].schema.$ref,
  '#/components/schemas/ObsPresentationReceipt');
assert.ok(api.components.schemas.ObsPresentationRequest.required.includes('expectedGeneration'));
assert.deepEqual(api.components.schemas.ObsPresentationRequest.properties.expectedGeneration.type,['string','null']);
assert.ok(api.components.schemas.ObsInfographicsSnapshot.properties.leaderPresentation.anyOf.some(schema=>schema.type==='null'));
assert.deepEqual(api.paths[presentations+'/{generation}'].delete.security,[{staffSession:[]}]);
writeFileSync(`${evidence}/openapi.json`,JSON.stringify(api));
const assets = [];
for (const name of ['obs-leaders.html','obs/leaders.mjs','obs/infographics.mjs','obs/infographics-model.mjs','obs/infographics.css','auth/staff-session.mjs']) {
  const source = readFileSync(new URL('../../main/resources/static/'+name,import.meta.url));
  assert.deepEqual(execFileSync('unzip',['-p',jar,'BOOT-INF/classes/static/'+name]),source);
  assert.deepEqual((await call('/'+name)).raw,source);
  assets.push({path:name,sha256:createHash('sha256').update(source).digest('hex'),sourceJarServedEqual:true});
}
const migration = 'V24__obs_infographics_presentations.sql';
const bytes = readFileSync(new URL('../../main/resources/db/migration/'+migration,import.meta.url));
assert.deepEqual(execFileSync('unzip',['-p',jar,'BOOT-INF/classes/db/migration/'+migration]),bytes);
assert.equal(sql("SELECT success FROM flyway_schema_history WHERE version='24'"),'t');
assert.deepEqual((await call('/staff-auth-v1.mjs')).raw,readFileSync(helper));
writeFileSync(`${evidence}/artifact-bytes.json`,JSON.stringify({jarSHA256:createHash('sha256').update(readFileSync(jar)).digest('hex'),
  assets,migration:{name:migration,sha256:createHash('sha256').update(bytes).digest('hex'),sourceJarEqual:true,flywayApplied:true}},null,2));

const {chromium} = await import(pathToFileURL(playwright).href);
const browser = await chromium.launch({executablePath:launcher,headless:true});
try {
  const staff = await browser.newContext(), page = await staff.newPage(), errors = [];
  await staff.addCookies([...cookies].map(([name,value])=>({name,value,url:origin,secure:true})));
  page.on('pageerror',error=>errors.push(error.message));
  page.on('request',request=>{
    if (!new URL(request.url()).pathname.startsWith(presentations)) return;
    const h = request.headers();
    assert.equal(h.authorization,undefined);
    assert.ok(h['x-ultistats-session-id'] && h['x-csrf-token']);
    wire.push({method:request.method(),capturedIdentity:true,csrf:true,bearer:false});
  });
  await page.goto(origin+'/obs-leaders.html');
  await page.selectOption('[name=mode]','exact');
  await page.fill('[name=matchId]',match);
  await page.click('button[type=submit]');
  const viewer = await browser.newPage({viewport:{width:1920,height:1080}});
  await viewer.goto(exactUrl);
  const box = viewer.locator('#infographics[data-graphic-kind=leaders]');
  const showRequest = page.waitForRequest(req=>new URL(req.url()).pathname===presentations && req.method()==='POST');
  await page.click('#show-leaders');
  const expired = (await showRequest).postDataJSON();
  await box.waitFor({state:'visible'});
  assert.equal(await box.locator('.person').count(),1);
  assert.match(await box.innerText(),/Нет результативных действий/);
  const firstBox = await box.boundingBox();
  await viewer.screenshot({path:`${evidence}/leaders-1920.png`});
  await viewer.waitForSelector('#infographics[data-category=defense]');
  assert.deepEqual(await box.boundingBox(),firstBox);
  await viewer.setViewportSize({width:320,height:180});
  await viewer.emulateMedia({reducedMotion:'reduce'});
  await viewer.reload();
  await viewer.waitForSelector('#infographics[data-category=defense]');
  assert.equal(await box.evaluate(el=>getComputedStyle(el).animationName),'none');
  assert.equal(await box.locator('.number').count(),0);
  assert.equal(await box.locator('.leader-detail').evaluate(el=>getComputedStyle(el).animationName),'none');
  await viewer.screenshot({path:`${evidence}/leaders-320x180.png`});
  await box.waitFor({state:'hidden',timeout:10500});
  assert.equal((await view()).leaderPresentation,null);
  assert.ok(Date.parse((await show(expired)).data.expiresAt) < Date.now());
  assert.equal((await view()).leaderPresentation,null);
  assert.equal(await viewer.locator('#infographics').isVisible(),(await view()).phase.kind!=='HIDDEN');
  assert.equal(sql('SELECT count(*) FROM obs_infographics_presentations'),String(Number(count)+1));
  await page.click('#show-leaders');
  await box.waitFor({state:'visible'});
  await page.click('#hide-leaders');
  await box.waitFor({state:'hidden'});
  let release, admitted;
  const delayed = new Promise(resolve=>{release=resolve;});
  const arrived = new Promise(resolve=>{admitted=resolve;});
  await page.route('**/infographics/presentations',async route=>{
    const response = await route.fetch();
    admitted(response.status());await delayed;
    await route.fulfill({response}).catch(()=>{});
  });
  await page.click('#show-leaders');
  assert.equal(await arrived,200);
  await staff.clearCookies();
  await page.waitForFunction(()=>document.querySelector('fieldset').disabled);
  release();
  assert.equal(await page.locator('#command-status').innerText(),'');
  assert.deepEqual(errors,[]);
  results.push('normal TLS frozen helper controls and real protected viewer: attack/defense, stable box, reduced motion, reconnect, expiry/hide restore and late reply after session loss');
} finally {await browser.close();}
writeFileSync(`${evidence}/http-browser-proof.json`,JSON.stringify({results,wire,ownSyntheticOnly:true,normalTLS:true},null,2));
console.log(results);
