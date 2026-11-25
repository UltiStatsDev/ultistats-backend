// Own synthetic fixture only. Credentials and bearer URLs stay in memory.
import assert from 'node:assert/strict';
import https from 'node:https';
import {readFileSync,writeFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {createHash,randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';

const {origin,evidence,ca,launcher,playwright,helper,jar,password,newPassword} = JSON.parse(readFileSync(0,'utf8'));
assert.match(origin, /^https:\/\/localhost:\d+$/);
const cookies = new Map();
const proof = [];
let identity = {};
async function call(path,{method='GET',body,token,staff=true,status=200,headers={}}={}) {
  const requestHeaders = {...headers};
  if (method !== 'GET') {
    requestHeaders.Origin = origin;
    requestHeaders['X-CSRF-TOKEN'] = (await call('/api/v1/auth/csrf')).data.token;
  }
  if (staff) {
    requestHeaders.Cookie = [...cookies].map(([name,value]) => `${name}=${value}`).join('; ');
    Object.assign(requestHeaders, identity);
  }
  if (token) requestHeaders.Authorization = `Bearer ${token}`;
  const payload = body === undefined ? undefined : JSON.stringify(body);
  if (payload !== undefined) {
    requestHeaders['Content-Type'] = 'application/json';
    requestHeaders['Content-Length'] = Buffer.byteLength(payload);
  }
  const reply = await new Promise((resolve,reject) => {
    const request = https.request(new URL(path,origin),{method,headers:requestHeaders,ca:readFileSync(ca)}, response => {
      const chunks = [];
      response.on('data', chunk => chunks.push(chunk));
      response.on('end', () => resolve({status:response.statusCode, headers:response.headers, raw:Buffer.concat(chunks)}));
    });
    request.on('error', reject);
    request.end(payload);
  });
  for (const entry of reply.headers['set-cookie'] ?? []) {
    if (staff) {
      const [pair] = entry.split(';'), split = pair.indexOf('=');
      cookies.set(pair.slice(0,split),pair.slice(split+1));
    }
  }
  const text = reply.raw.toString();
  assert.equal(reply.status,status,`${method} ${path} (${body?.type ?? ''}): ${text.match(/"code":"([A-Z_]+)"/)?.[1] ?? ''}`);
  return {...reply,data:reply.headers['content-type']?.includes('json') && text ? JSON.parse(text) : null};
}

const session = (await call('/api/v1/auth/session')).data;
const login = (await call('/api/v1/auth/login',{
  method:'POST',headers:{'X-UltiStats-Installation-Id':session.installationId},body:{login:'obs259-owner',password},
})).data;
function capture(value) {
  identity = {'X-UltiStats-Installation-Id':value.installationId,'X-UltiStats-Account-Id':value.account.id,'X-UltiStats-Session-Id':value.sessionId};
}
capture(login);
assert.equal(login.state,'PASSWORD_CHANGE_REQUIRED');
capture((await call('/api/v1/auth/password',{method:'POST',body:{currentPassword:password,newPassword}})).data);

const create = async (path,body) => (await call(`/api/v1/${path}`,{method:'POST',status:201,body})).data;
const teams = [];
for (const name of ['Север', 'Юг']) teams.push(await create('teams',{name}));
for (const team of teams) {
  for (const [index,firstName] of ['Игрок А','Игрок Б'].entries()) {
    const player = await create('players',{firstName,lastName:team.name});
    await call(`/api/v1/teams/${team.id}/players/${player.id}`,{method:'PUT',body:{number:index+10}});
  }
}
const timestamp = (base,s=0) => new Date(base + s * 1000).toISOString();
const epoch = Math.floor(Date.now()/1000)*1000 - 1_500_000;
async function matchAt(base) {
  const {id,teams:rosters} = await create('matches',{teamIds:teams.map(team=>team.id),playersPerTeam:2});
  const path = `/api/v1/matches/${id}`;
  await call(path+'/start',{method:'POST',body:{timestamp:timestamp(base)}});
  const players = rosters.map(team=>team.participants.filter(player=>player.kind==='PLAYER')
    .sort((a,b)=>a.number-b.number).map(player=>player.participantId));
  async function event(s,type,from,to) {
    const actor = to ? {fromParticipantId:from,toParticipantId:to}
      : type.startsWith('TIMEOUT') ? {teamId:from} : {participantId:from};
    await create(`matches/${id}/events`,{type,occurredAt:timestamp(base,s),eventId:randomUUID(),...actor});
  }
  return {id,path,players,event,finish:s=>call(path+'/end',{method:'POST',body:{timestamp:timestamp(base,s)}}),
    remove:()=>call(path,{method:'DELETE',status:204})};
}
const issue = (scope,matchId) => create('obs/source-keys',{scope,...(matchId ? {matchId} : {}),operationId:randomUUID()});
const tokenOf = key => new URL(key.url).hash.slice('#key='.length);
const read = async (key,target,status=200) => {
  const reply = await call('/api/v1/obs/sources/infographics/'+target,{staff:false,token:tokenOf(key),status});
  if (status===200) assert.match(reply.headers['cache-control'],/no-store/);
  return target==='auto' ? reply.data.snapshot : reply.data;
};

const main = await matchAt(epoch);
const auto = await issue('AUTO_INFOGRAPHICS');
const exact = await issue('EXACT_INFOGRAPHICS',main.id);
const old = await issue('EXACT_SCOREBOARD',main.id);
assert.equal((await read(exact,main.id)).postmatch,null);
await call(main.path+'/statistics',{staff:false,token:tokenOf(exact),status:401});
await read(old,main.id,403);
await call(`/api/v1/obs/previews/infographics/${main.id}`,{token:tokenOf(exact),staff:false,status:401});
const [[l0,l1],[r0]] = main.players;
for (const args of [
  [0,'PULL',r0], [10,'PICKUP',l0], [70,'PASS',l0,l1], [100,'TIMEOUT_START',teams[0].id],
  [130,'TIMEOUT_END',teams[0].id], [160,'BLOCK',l1,r0], [170,'PICKUP',r0], [230,'BLOCK_MARKER',r0,l0],
  [240,'PICKUP',l0], [300,'BLOCK_FIELD',l0,r0], [310,'PICKUP',r0], [370,'INTERCEPTION',r0,l0],
  [430,'INCOMPLETE_PASS',l0], [440,'PICKUP',r0], [500,'INTERCEPTION',r0,l0],
  [560,'PASS',l0,l1], [620,'GOAL',l1,l0], [630,'PULL',l0], [640,'PICKUP',r0], [700,'CALLAHAN',r0,l0],
]) await main.event(...args);
await main.finish(790);

async function parity(match,key,target,times,latest) {
  const ordinary = (await call(match.path+'/statistics')).data;
  const snapshot = await read(key,target);
  assert.equal(snapshot.matchId,match.id);
  assert.equal(snapshot.status,'FINISHED');
  const panel = snapshot.postmatch;
  assert.equal(panel.statsAsOfEventAt === null ? null : Date.parse(panel.statsAsOfEventAt),latest === null ? null : Date.parse(latest));
  const out = ordinary.teams.map(({teamId,attack,defense,time}) => ({
    teamId, score:attack.score, accuracy:attack.allPasses ? attack.completePasses / attack.allPasses : null,
    possessionTimeMs:time.possessionTimeMs, possession:times[0]+times[1] ? time.possessionTimeMs/(times[0]+times[1]) : null,
    metrics:{accuratePasses:attack.completePasses,inaccuratePasses:attack.allPasses-attack.completePasses,
      blocks:defense.blocks+defense.blocksAsMarker+defense.blocksAsFieldPlayer,
      interceptions:defense.interceptions,callahans:defense.callahans,breaks:attack.breaks},
  }));
  assert.deepEqual(ordinary.teams.map(team=>team.time.possessionTimeMs),times);
  assert.deepEqual(panel.teams,out);
  proof.push({times,canonicalEventHorizon:latest,ordinaryParity:true});
  return snapshot;
}
const finished = await parity(main,exact,main.id,[360000,240000],timestamp(epoch,700));
assert.deepEqual(finished.postmatch.teams.map(team=>Object.values(team.metrics)),[[3,3,1,2,1,1],[0,4,2,0,0,0]]);
assert.deepEqual((await read(auto,'auto')).postmatch,finished.postmatch);
assert.deepEqual((await call(`/api/v1/obs/previews/infographics/${main.id}`)).data.postmatch,finished.postmatch);

const {chromium} = await import(pathToFileURL(playwright).href);
const browser = await chromium.launch({executablePath:launcher,headless:true});
try {
  const viewer = await browser.newContext({viewport:{width:1920,height:1080}});
  const page = await viewer.newPage();
  const errors = [], viewerPaths = [];
  page.on('pageerror', error=>errors.push(error.message));
  page.on('request', request=>viewerPaths.push(new URL(request.url()).pathname));
  const labels = () => page.locator('.postmatch-ring-value').allTextContents();
  const show = async expected => {
    await page.waitForFunction(labels => [...document.querySelectorAll('.postmatch-ring-value')].map(n=>n.textContent).join() === labels.join(),expected);
    assert.deepEqual(await labels(),expected);
  };
  await page.goto(auto.url);
  await show(['50.0 %','60.0 %','0.0 %','40.0 %']);
  assert.equal(await page.locator('.postmatch-row').count(),6);
  assert.equal(await page.locator('#infographics').evaluate(node=>getComputedStyle(node).animationName),'postmatch-appear');
  assert.deepEqual(await page.locator('.postmatch-row').nth(1).locator('.postmatch-fill').evaluateAll(bars=>bars.map(bar=>bar.style.width)),['75%','100%']);
  const bounds = await page.locator('#infographics').boundingBox();
  await page.waitForTimeout(1250);
  assert.deepEqual(await page.locator('#infographics').boundingBox(),bounds);
  await page.screenshot({path:`${evidence}/postmatch-1920.png`});

  const staff = await browser.newContext({viewport:{width:1280,height:720}});
  await staff.addCookies([...cookies].map(([name,value])=>({name,value,url:origin,secure:true})));
  const preview = await staff.newPage();
  const privateReads = [];
  preview.on('request', request=>{
    if (new URL(request.url()).pathname.startsWith('/api/v1/obs/previews/')) {
      const headers = request.headers();
      privateReads.push([Boolean(headers.authorization), Boolean(headers['x-ultistats-session-id'])]);
    }
  });
  await preview.goto(`${origin}/obs-preview/infographics.html?matchId=${main.id}`);
  await preview.waitForSelector('.postmatch-ring');
  assert.equal(new URL(preview.url()).hash,'');
  assert.deepEqual(await preview.locator('.postmatch-ring-value').allTextContents(),await labels());
  assert.ok(privateReads.length);
  assert.ok(privateReads.every(([bearer,session])=>!bearer && session));
  await preview.waitForTimeout(240);
  await preview.screenshot({path:`${evidence}/postmatch-private-preview.png`});
  await staff.clearCookies();
  await preview.waitForFunction(()=>document.querySelector('#infographics').hidden);

  for (const viewport of [{width:640,height:1080},{width:320,height:180}]) {
    await page.setViewportSize(viewport);
    await page.emulateMedia({reducedMotion:'reduce'});
    const box = await page.locator('#infographics').boundingBox();
    assert.ok(box.x>=0 && box.y>=0 && box.x+box.width<=viewport.width && box.y+box.height<=viewport.height);
    assert.ok(await page.locator('#infographics').evaluate(node=>node.scrollHeight<=node.clientHeight+1));
    assert.equal(await page.locator('#infographics').evaluate(node=>getComputedStyle(node).animationName),'none');
    await page.screenshot({path:`${evidence}/postmatch-${viewport.width}.png`});
  }

  const leaders = (await call('/api/v1/obs/infographics/leaders/auto')).data.snapshot;
  assert.equal(leaders.matchId,main.id);
  const {expectedInputRevision,expectedSettingsRevision,expectedGeneration,canShow} = leaders.presentation;
  assert.equal(canShow,true);
  const receipt = (await call('/api/v1/obs/infographics/presentations',{method:'POST',body:{
    binding:'AUTO',expectedMatchId:main.id,expectedInputRevision,expectedSettingsRevision,expectedGeneration,operationId:randomUUID(),
  }})).data;
  assert.equal((await read(auto,'auto')).leaderPresentation.generation,receipt.generation);
  await page.waitForSelector('[data-graphic-kind="leaders"]');
  assert.equal(await page.locator('.postmatch-panel').count(),0);
  await show(['50.0 %','60.0 %','0.0 %','40.0 %']);
  assert.ok(Date.now() >= Date.parse(receipt.expiresAt));
  assert.equal((await read(auto,'auto')).leaderPresentation,null);

  const next = await matchAt(epoch+950000);
  await page.waitForSelector('.postmatch-panel',{state:'detached'});
  assert.equal((await read(auto,'auto')).postmatch,null);
  const [[a],[b,b1]] = next.players;
  await next.event(0,'PULL',b);
  await next.event(1,'PICKUP',a);
  await next.event(2,'INTERCEPTION',a,b);
  await next.event(4,'GOAL',b,b1);
  await next.finish(8);
  await parity(next,auto,'auto',[1000,2000],timestamp(epoch+950000,4));
  await show(['0.0 %','33.3 %','100.0 %','66.7 %']);
  await next.remove();
  await show(['50.0 %','60.0 %','0.0 %','40.0 %']);

  const one = await matchAt(epoch+960000);
  const [[oneLeft],[oneRight,oneReceiver]] = one.players;
  await one.event(0,'PULL',oneLeft);
  await one.event(2,'PICKUP',oneRight);
  await one.event(6,'GOAL',oneRight,oneReceiver);
  await one.finish(8);
  await parity(one,auto,'auto',[0,4000],timestamp(epoch+960000,6));
  await show(['—','0.0 %','100.0 %','100.0 %']);
  await one.remove();

  const empty = await matchAt(epoch+970000);
  const [[zeroLeft,zeroReceiver],[zeroRight]] = empty.players;
  // A legal finished point can have zero time on both sides when the events share a timestamp.
  await empty.event(0,'PULL',zeroRight);
  await empty.event(0,'PICKUP',zeroLeft);
  await empty.event(0,'GOAL',zeroLeft,zeroReceiver);
  await empty.finish(8);
  await parity(empty,auto,'auto',[0,0],timestamp(epoch+970000));
  await show(['100.0 %','—','—','—']);
  assert.equal(await page.locator('.postmatch-no-possession').textContent(),'Нет данных о владении.');
  await page.screenshot({path:`${evidence}/postmatch-no-possession-320.png`});
  assert.ok(await page.locator('.postmatch-row').nth(1).locator('.postmatch-fill').evaluateAll(bars=>bars.every(bar=>bar.style.width==='0%')));
  await empty.remove();
  await show(['50.0 %','60.0 %','0.0 %','40.0 %']);
  await main.remove();
  await page.waitForSelector('#infographics',{state:'hidden'});
  assert.equal(await read(auto,'auto'),null);
  await read(exact,main.id,404);
  assert.deepEqual(errors,[]);
  assert.ok(viewerPaths.filter(path=>path.startsWith('/api/')).every(path=>path==='/api/v1/obs/sources/infographics/auto'));
} finally { await browser.close(); }

const openapi = (await call('/v3/api-docs')).data;
assert.ok(openapi.components.schemas.ObsInfographicsSnapshot.properties.postmatch);
assert.ok(openapi.components.schemas.ObsInfographicsPostmatch.properties.statsAsOfEventAt);
writeFileSync(`${evidence}/openapi.json`,JSON.stringify(openapi));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const helperHash = sha((await call('/staff-auth-v1.mjs',{staff:false})).raw);
assert.equal(helperHash,sha(readFileSync(helper)));
assert.equal(helperHash,'f20e7cdbfd095f00d63fb0f472ed1726cd601b434f5048da765dd53a81175d4a');
const assets = [];
for (const path of ['obs-sources/infographics.html','obs-preview/infographics.html','obs/infographics.mjs',
  'obs/infographics-model.mjs','obs/infographics.css','obs/leaders.mjs','obs-leaders.html',
  'obs/infographics-postmatch.mjs','obs/infographics-postmatch.css','obs/protected-source-client.mjs','obs/staff-preview.mjs']) {
  const source = readFileSync(new URL('../../main/resources/static/'+path,import.meta.url));
  assert.deepEqual(execFileSync('unzip',['-p',jar,'BOOT-INF/classes/static/'+path]),source,path+' packaged');
  assert.deepEqual((await call('/'+path,{staff:path.startsWith('obs-preview/')||['obs/staff-preview.mjs','obs/leaders.mjs','obs-leaders.html'].includes(path)})).raw,source,path+' served');
  assets.push({path,sha256:sha(source),sourceJarServedEqual:true});
}
writeFileSync(`${evidence}/artifact-bytes.json`,JSON.stringify({jarSHA256:sha(readFileSync(jar)),helperSHA256:helperHash,assets},null,2));
writeFileSync(`${evidence}/http-browser-proof.json`,JSON.stringify({
  postmatch:proof, normalAPI:true, normalTLS:true, ownedSyntheticOnly:true, frozenHelperVerified:true,
  checks:['six counters + scales','protection + preview','same panel bounds','reduced motion + narrow frames','leader expiry restores current FINISHED','AUTO switch + deletion'],
},null,2));
console.log('Canonical PostgreSQL statistics, actual HTTPS infographic renderer and frozen-helper preview verified.');
