// Own synthetic PostgreSQL + real JAR/HTTPS only. Input contains fixture paths, never human data.
import assert from 'node:assert/strict';
import https from 'node:https';
import {readFileSync,writeFileSync} from 'node:fs';
import {execFileSync,spawn} from 'node:child_process';
import {createHash,randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';
const input=JSON.parse(readFileSync(0,'utf8'));
const {origin,pg,evidence,ca,launcher,playwright,helper,jar}=input;
assert.match(pg,/^obs255-pg-/);
const sql=text=>execFileSync('docker',['exec','-i',pg,'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','obs255','-d','obs255'],{input:text,encoding:'utf8'}).trim();
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
let context=(await call('/api/v1/auth/login',{method:'POST',headers:{'X-UltiStats-Installation-Id':installation},body:{login:'obs255-owner',password:input.password??'Synthetic-obs255-start'}})).data;
function capture(value) {
  identity={'X-UltiStats-Installation-Id':value.installationId,'X-UltiStats-Account-Id':value.account.id,'X-UltiStats-Session-Id':value.sessionId};
}
capture(context);
if(context.state==='PASSWORD_CHANGE_REQUIRED') context=(await call('/api/v1/auth/password',{method:'POST',body:{currentPassword:'Synthetic-obs255-start',newPassword:'Synthetic-obs255-permanent'}})).data;
capture(context);
// This database belongs exclusively to this B fixture.
sql('UPDATE matches SET deleted_at=now();');
const match=randomUUID(), teams=[randomUUID(),randomUUID()], players=[[],[]], slot=randomUUID();
sql(`INSERT INTO matches(id,players_per_team,started_at) VALUES ('${match}',7,now()-interval '30 seconds');`);
for(let side=0;side<2;side++) {
  sql(`INSERT INTO teams(id,name) VALUES ('${teams[side]}','Global name'); INSERT INTO match_teams(match_id,team_id,position,score,team_name) VALUES ('${match}','${teams[side]}',${side+1},0,'Snapshot team ${side}');`);
  for(let n=0;n<(side?9:12);n++) {
    const id=randomUUID();players[side].push(id);
    sql(`INSERT INTO match_participants(match_id,participant_id,team_id,kind,first_name,last_name,number) VALUES ('${match}','${id}','${teams[side]}','PLAYER','Снимок${n}','ОченьДлиннаяФамилияБезПробеловДляПроверкиКарточки',${n});`);
  }
  for(let n=1;n<=2;n++) sql(`INSERT INTO match_participants(match_id,participant_id,team_id,kind,unknown_slot) VALUES ('${match}','${randomUUID()}','${teams[side]}','UNKNOWN',${n});`);
}
const path=`/api/v1/matches/${match}`, capability={'X-UltiStats-Point-Lineups':'3'};
const pointRead=async()=>(await call(path+'/point-lineups',{headers:capability})).data;
const point=(await pointRead()).points[0];
const key=(await call('/api/v1/obs/source-keys',{method:'POST',expected:201,body:{scope:'EXACT_INFOGRAPHICS',matchId:match,operationId:randomUUID()}})).data;
const read=async()=>(await call(`/api/v1/obs/sources/infographics/${match}`,{staff:false,token:key.url.split('#key=')[1]})).data;
const initial=await read();assert.equal(initial.phase.kind,'ROSTER');assert.deepEqual(initial.teams.map(t=>t.roster.length),[12,9]);
const selection={expectedLogRevision:0,teams:teams.map((teamId,side)=>({teamId,entries:[...players[side].slice(0,side?4:6).map(participantId=>({kind:'PLAYER',participantId})),...(!side?[{kind:'TEMPORARY',slotId:slot,number:7}]:[])]}))};
const confirm=async(pointId,body)=>(await call(path+'/point-lineups/'+pointId,{method:'PUT',headers:capability,body})).data;
await confirm(point.pointId,selection);
const epoch=sql(`SELECT confirmed_at FROM match_point_lineups WHERE match_id='${match}' AND point_id='${point.pointId}';`);
assert.ok(epoch);
await confirm(point.pointId,selection);
assert.equal(sql(`SELECT confirmed_at FROM match_point_lineups WHERE match_id='${match}' AND point_id='${point.pointId}';`),epoch);
let snapshot=await read();assert.equal(snapshot.phase.kind,'LINEUP');assert.equal(snapshot.phase.anchorKind,'CONFIRMED');
assert.deepEqual(snapshot.teams.map(t=>t.places.length),[7,4]);assert.notEqual(snapshot.presentationRevision,initial.presentationRevision);
assert.deepEqual(snapshot.teams[0].places.map(p=>p.participantId),[...players[0].slice(0,6),slot]);
const unknown=snapshot.teams[0].places[6];assert.equal(unknown.name,'Игрок неизвестен');assert.equal(unknown.number,null);assert.equal(unknown.temporary,true);
assert.equal((await read()).phase.startedAt,snapshot.phase.startedAt);
results.push('actual PG + HTTP: canonical seven/four selected places from twelve/nine roster, TEMP anonymized, durable retry/reconnect epoch');
const openapi=(await call('/v3/api-docs')).data;writeFileSync(`${evidence}/openapi.json`,JSON.stringify(openapi));
const assets=[];
for(const asset of ['obs/infographics.mjs','obs/infographics-model.mjs','obs/infographics.css','obs-sources/infographics.html']) {
  const source=readFileSync(new URL('../../main/resources/static/'+asset,import.meta.url));
  assert.deepEqual(execFileSync('unzip',['-p',jar,'BOOT-INF/classes/static/'+asset]),source);
  assert.deepEqual((await call('/'+asset,{staff:false})).raw,source);
  assets.push({path:asset,sha256:createHash('sha256').update(source).digest('hex'),sourceJarServedEqual:true});
}
const migration=readFileSync(new URL('../../main/resources/db/migration/V22__point_lineup_confirmation_epoch.sql',import.meta.url));
assert.deepEqual(execFileSync('unzip',['-p',jar,'BOOT-INF/classes/db/migration/V22__point_lineup_confirmation_epoch.sql']),migration);
writeFileSync(`${evidence}/artifact-bytes.json`,JSON.stringify({jarSHA256:createHash('sha256').update(readFileSync(jar)).digest('hex'),assets,migrationSHA256:createHash('sha256').update(migration).digest('hex')},null,2));
const event=async(type,from,to)=> (await call(path+'/events',{method:'POST',headers:capability,expected:201,body:{type,...(to?{fromParticipantId:from,toParticipantId:to}:{participantId:from}),occurredAt:new Date().toISOString(),eventId:randomUUID()}})).data;
const undo=()=>call(path+'/events/undo',{method:'POST',headers:capability});
const {chromium}=await import(pathToFileURL(playwright).href);
const browser=await chromium.launch({executablePath:launcher,headless:true});
try {
  const context=await browser.newContext({viewport:{width:1920,height:1080}}), page=await context.newPage(), errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.goto(key.url);await page.waitForSelector('.person');
  assert.equal(await page.locator('h2').textContent(),'Состав поинта');
  assert.deepEqual(await page.locator('.team-grid').evaluateAll(ns=>ns.map(n=>n.children.length)),[5,4]);
  const bounds=await page.locator('#infographics').boundingBox();
  await page.waitForFunction(()=>document.querySelector('.team-strip p')?.textContent==='6–7 из 7');
  assert.deepEqual(await page.locator('#infographics').boundingBox(),bounds);
  const card=page.locator(`[data-participant-id="${slot}"]`);
  assert.equal(await card.locator('.photo').textContent(),'?');assert.equal(await card.locator('.number').count(),0);
  assert.equal(await card.locator('.name').textContent(),'Игрок неизвестен');
  await page.waitForTimeout(250);await page.screenshot({path:`${evidence}/lineup-1920.png`});
  await page.reload();await page.waitForSelector('.person');
  assert.equal(await page.locator('h2').textContent(),'Состав поинта');
  await page.setViewportSize({width:320,height:180});await page.emulateMedia({reducedMotion:'reduce'});
  const small=await page.locator('#infographics').boundingBox();assert.ok(small.x>=0&&small.y>=0&&small.x+small.width<=320&&small.y+small.height<=180);
  assert.equal(await page.locator('.team-grid').first().evaluate(n=>getComputedStyle(n).animationName),'none');
  await page.screenshot({path:`${evidence}/lineup-320.png`});
  await event('PULL',players[1][0]);await page.waitForFunction(()=>document.querySelector('#infographics').hidden);
  assert.equal((await read()).phase.kind,'HIDDEN');
  await event('PICKUP',players[0][0]);await event('PASS',players[0][0],players[0][1]);
  const goal=await event('GOAL',players[0][1],players[0][2]);
  const next=(await pointRead()).points.at(-1);assert.notEqual(next.pointId,point.pointId);
  assert.equal((await read()).phase.kind,'HIDDEN');
  const empty={expectedLogRevision:4,teams:teams.map(teamId=>({teamId,entries:[]}))};
  await confirm(next.pointId,empty);snapshot=await read();assert.equal(snapshot.phase.kind,'LINEUP');
  assert.deepEqual(snapshot.teams.map(t=>t.places),[[],[]]);
  await page.waitForFunction(()=>document.querySelector('h2')?.textContent==='Состав поинта'&&document.querySelectorAll('.person').length===0);
  await call(`${path}/point-lineups/${point.pointId}/slots/${slot}`,{method:'PATCH',headers:capability,body:{participantId:players[0][6],expectedLineupRevision:1,expectedClosingEventId:goal.id}});
  await undo();assert.equal((await read()).phase.kind,'HIDDEN');
  for(let i=0;i<3;i++) await undo();
  snapshot=await read();assert.equal(snapshot.phase.pointId,point.pointId);assert.equal(snapshot.phase.lineupRevision,2);
  assert.equal(snapshot.phase.kind,'LINEUP');assert.equal(snapshot.teams[0].places[6].name,'Игрок неизвестен');assert.equal(snapshot.teams[0].places[6].number,null);
  assert.notEqual(snapshot.presentationRevision,initial.presentationRevision);
  results.push('actual domain events: PULL hides, GOAL creates canonical NEXT, confirmed empty state; identification + undo restores only canonical point, resolved TEMP still anonymous');
  // Explicit legacy fixture is a NULL writer, not a manufactured historic confirmation.
  sql(`UPDATE match_point_lineups SET confirmed_at=NULL WHERE match_id='${match}' AND point_id='${point.pointId}';`);
  snapshot=await read();assert.equal(snapshot.phase.anchorKind,'LEGACY_POINT_OPEN');
  const fallback=snapshot.phase.startedAt;assert.equal((await read()).phase.startedAt,fallback);
  sql(`UPDATE matches SET started_at=now() WHERE id='${match}';`);
  snapshot=await read();assert.equal(snapshot.phase.kind,'ROSTER');
  await page.waitForFunction(()=>document.querySelector('h2')?.textContent==='Ростер команд');
  sql(`UPDATE matches SET started_at=now()-interval '16 seconds' WHERE id='${match}';`);
  await page.waitForFunction(()=>document.querySelector('h2')?.textContent==='Состав поинта');
  assert.equal((await read()).phase.kind,'LINEUP');
  assert.deepEqual(errors,[]);
  results.push('real served renderer: five-card pages/short side hold, TEMP exact label/no jersey, stable frame, reload, narrow/reduced motion, empty state, first roster pass, legacy NULL stable fallback');
} finally {await browser.close();}
// Original RR snapshot includes lineup metadata, roster and canonical current point.
const lock=spawn('docker',['exec','-i',pg,'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','obs255','-d','obs255']);
const locked=new Promise(resolve=>lock.stdout.on('data',b=>{if(b.toString().includes('LOCKED'))resolve();}));
lock.stdin.write("BEGIN; LOCK TABLE match_participants IN ACCESS EXCLUSIVE MODE;\n\\echo LOCKED\n");await locked;
const pending=read();let waiting=false;
for(let n=0;n<100;n++) {waiting=sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'SELECT participant_id,%';")==='1';if(waiting)break;await new Promise(r=>setTimeout(r,20));}
assert.equal(waiting,true);
const committed=new Promise((resolve,reject)=>lock.on('exit',c=>c===0?resolve():reject(Error('writer failed'))));
lock.stdin.end(`UPDATE match_point_lineups SET revision=revision+1 WHERE match_id='${match}' AND point_id='${point.pointId}'; COMMIT;\n`);await committed;
assert.equal((await pending).phase.lineupRevision,2);assert.equal((await read()).phase.lineupRevision,3);
results.push('actual PostgreSQL blocked-reader/concurrent-writer proof: original REPEATABLE_READ includes lineup revision');
writeFileSync(`${evidence}/http-browser-proof.json`,JSON.stringify({results,normalTLS:true,ownSyntheticOnly:true},null,2));
console.log(results);
