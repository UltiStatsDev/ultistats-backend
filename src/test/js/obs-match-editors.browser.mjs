// Own synthetic PostgreSQL + real JAR/HTTPS only. Input contains fixture paths, never human data.
import assert from 'node:assert/strict';
import https from 'node:https';
import {readFileSync,writeFileSync} from 'node:fs';
import {execFileSync,spawn} from 'node:child_process';
import {createHash,randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';
const input=JSON.parse(readFileSync(0,'utf8'));
const {origin,pg,evidence,ca,launcher,playwright,jar}=input;
assert.match(pg,/^obs271-pg-/);
assert.equal(new URL(origin).hostname,'localhost');
const sql=text=>execFileSync('docker',['exec','-i',pg,'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','obs271','-d','obs271'],{input:text,encoding:'utf8'}).trim();
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
let context=(await call('/api/v1/auth/login',{method:'POST',headers:{'X-UltiStats-Installation-Id':installation},body:{login:'obs271-owner',password:input.password??'Synthetic-obs271-start'}})).data;
function capture(value) {
  identity={'X-UltiStats-Installation-Id':value.installationId,'X-UltiStats-Account-Id':value.account.id,'X-UltiStats-Session-Id':value.sessionId};
}
capture(context);
if(context.state==='PASSWORD_CHANGE_REQUIRED') context=(await call('/api/v1/auth/password',{method:'POST',body:{currentPassword:'Synthetic-obs271-start',newPassword:'Synthetic-obs271-permanent'}})).data;
capture(context);

const pairA=[randomUUID(),randomUUID()], pairB=[randomUUID(),randomUUID()], unrelated=randomUUID();
const matchA=randomUUID(), matchB=randomUUID();
for(const [match,pair,name] of [[matchA,pairA,'A'],[matchB,pairB,'B']]) {
 sql(`INSERT INTO matches(id,players_per_team) VALUES ('${match}',7);`);
 for(let i=0;i<2;i++) {
  const team=pair[i], label=`${name}-${i+1}`;
  sql(`INSERT INTO teams(id,name) VALUES ('${team}','${label}');
       INSERT INTO match_teams(match_id,team_id,position,team_name) VALUES ('${match}','${team}',${i+1},'${label}');`);
 }
 await call(`/api/v1/obs/scoreboard/${match}/profile`,{method:'PUT',body:{shortNames:{[pair[0]]:`Saved ${name}`},teamColors:{[pair[1]]:'#123abc'}}});
}
const configPath='/api/v1/obs/config', config=(await call(configPath)).data;
config.teamColors={[unrelated]:'#123456',[pairA[1]]:'#a10000',[pairB[1]]:'#b10000'};
await call(configPath,{method:'PUT',body:config});
const scoped=(id,colors,options={})=>call(`${configPath}?matchId=${id}`,{method:'PUT',body:{...config,teamColors:colors},...options});
await call(configPath,{staff:false,expected:401});
await scoped(matchA,{}, {csrf:false,expected:403});
await scoped(matchA,{[pairB[0]]:'#abcdef'},{expected:400});
await scoped(randomUUID(),{}, {expected:404});
await Promise.all([
 scoped(matchA,{[pairA[0]]:'#a00000',[pairA[1]]:'#a10000'}),
 scoped(matchB,{[pairB[0]]:'#b00000',[pairB[1]]:'#b10000'}),
]);
assert.deepEqual((await call(configPath)).data.teamColors,{...config.teamColors,[pairA[0]]:'#a00000',[pairB[0]]:'#b00000'});
results.push('Real PostgreSQL concurrent scoped HTTP writes preserve both pairs and the unrelated color. Invalid scope and absent CSRF rejected.');

const {chromium}=await import(pathToFileURL(playwright).href);
const browser=await chromium.launch({executablePath:launcher,headless:true});
try {
 const staff=await browser.newContext({viewport:{width:1280,height:1000}}), page=await staff.newPage(), errors=[];
 page.setDefaultTimeout(10000);
 await staff.addCookies([...cookies].map(([name,value])=>({name,value,url:origin,secure:true})));
 page.on('pageerror',e=>errors.push(e.message));
 staff.on('request',request=>{
  const url=new URL(request.url());
  if(url.pathname.startsWith('/api/v1/obs/')&&request.method()!=='GET')
   wire.push({path:url.pathname,query:url.search,method:request.method(),session:!!request.headers()['x-ultistats-session-id'],bearer:!!request.headers().authorization});
 });
 const waitForMatch=id=>page.waitForFunction(value=>document.querySelector('#editor-context').dataset.matchId===value,id);
 const log=id=>page.locator(`[data-log-color="${id}"]`);
 const board=(type,id)=>page.locator(`[data-scoreboard-${type}="${id}"]`);
 const open=async id=>{
  await page.selectOption('#links-mode','exact');
  await page.fill('#links-match',id);
  await page.locator('#links-open').click();
 };
 const assertPair=async (id,pair,name)=>{
  await waitForMatch(id);
  await board('name',pair[0]).waitFor();
  for(const type of ['log-color','scoreboard-color']) {
   assert.deepEqual(await page.locator(`[data-${type}]`).evaluateAll((nodes,attribute)=>nodes.map(node=>node.getAttribute(attribute)),`data-${type}`),pair);
  }
  assert.equal(await board('name',pair[0]).inputValue(),name);
  assert.ok((await page.locator('#scoreboard-preview').getAttribute('src')).endsWith(`?matchId=${id}`));
 };
 const save=async (selector,path,query='')=>{
  const response=page.waitForResponse(r=>r.request().method()==='PUT'&&new URL(r.url()).pathname===path&&new URL(r.url()).search===query);
  await page.locator(selector).click();assert.equal((await response).status(),200);
  await page.waitForFunction(selector=>!document.querySelector(selector).matches(':disabled'),selector);
 };
 await page.goto(origin+'/obs-admin.html');
 for(const fold of ['log-settings','scoreboard-settings','color-settings'])
  await page.locator(`#${fold} > summary`).click();
 await waitForMatch('');
 assert.ok(await page.locator('#team-colors').evaluate(el=>el.disabled));
 assert.equal(await page.locator('[data-log-color], [data-scoreboard-color]').count(),0);
 await page.locator('#maxRows').fill('7');
 await save('#save',configPath,'?preserveTeamColors=true');
 assert.equal((await call(configPath)).data.teamColors[unrelated],'#123456');

 sql(`UPDATE matches SET started_at=now()-interval '1 minute' WHERE id='${matchA}';`);
 await assertPair(matchA,pairA,'Saved A');
 assert.equal(await log(pairA[0]).inputValue(),'#a00000');
 await log(pairA[0]).fill('#aa3412');
 await board('name',pairA[0]).fill('Draft A');

 // Real response from A is withheld after it leaves the backend, then released after B binds.
 const acquired=Promise.withResolvers(), release=Promise.withResolvers(), completed=Promise.withResolvers();
 await page.route(`**/api/v1/obs/previews/scoreboard/${matchA}`,async route=>{
  if(route.request().frame()!==page.mainFrame())return route.continue();
  try {
   const response=await route.fetch();
   acquired.resolve(response.status());
   await release.promise;
   await route.fulfill({response});
  } catch(error) {if(!page.isClosed()) acquired.reject(error);}
  finally {completed.resolve();}
 });
 await open(matchA);assert.equal(await acquired.promise,200);
 await open(matchB);await assertPair(matchB,pairB,'Saved B');
 release.resolve();await completed.promise;
 await page.unroute(`**/api/v1/obs/previews/scoreboard/${matchA}`);
 await assertPair(matchB,pairB,'Saved B');
 assert.equal(wire.length,1,'header and AUTO updates made no mutations');

 await log(pairB[0]).fill('#008800');
 await page.getByRole('button',{name:'Автоцвет журнала · B-2'}).click();
 await board('color',pairB[0]).fill('#246bca');
 await board('name',pairB[0]).fill('New B');
 await save('#save',configPath,`?matchId=${matchB}`);
 await save('#scoreboard-save',`/api/v1/obs/scoreboard/${matchB}/profile`);
 assert.deepEqual((await call(configPath)).data.teamColors,{
  [unrelated]:'#123456',[pairA[0]]:'#a00000',[pairA[1]]:'#a10000',[pairB[0]]:'#008800',
 });
 assert.equal((await call(`/api/v1/obs/scoreboard/${matchB}`)).data.profile.teamColors[pairB[0]],'#246bca');
 assert.equal((await call(`/api/v1/obs/scoreboard/${matchA}`)).data.profile.shortNames[pairA[0]],'Saved A');
 await open(matchA);await assertPair(matchA,pairA,'Draft A');
 assert.equal(await log(pairA[0]).inputValue(),'#aa3412');
 await page.selectOption('#links-mode','auto');await assertPair(matchA,pairA,'Draft A');
 sql(`UPDATE matches SET started_at=now() WHERE id='${matchB}';`);
 await assertPair(matchB,pairB,'New B');
 assert.equal(wire.length,3);
 assert.ok(wire.every(r=>r.method==='PUT'&&r.session&&!r.bearer));
 results.push('Real Chromium header: no match, AUTO starts A, stale EXACT A response ignored, B profile/colors save/reset, A drafts restored, server AUTO resolves to B, no key or on-air writes.');

 await staff.clearCookies();
 await page.waitForFunction(()=>document.querySelector('#save').disabled&&document.querySelector('#scoreboard-fields').disabled);
 assert.equal(await page.locator('#scoreboard-preview').getAttribute('src'),'about:blank');
 assert.deepEqual(errors,[]);
 results.push('Session loss disables config/profile writes and clears the editor frame.');
 await staff.close();
}finally{await browser.close();}
const assets=[];
for(const name of ['obs-admin.html','obs/admin.mjs','obs/scoreboard-admin.mjs','obs/active-links-admin.mjs']) {
 const source=readFileSync(new URL('../../main/resources/static/'+name,import.meta.url));
 assert.deepEqual(execFileSync('unzip',['-p',jar,'BOOT-INF/classes/static/'+name]),source);
 assert.deepEqual((await call('/'+name)).raw,source);
 assets.push({name,sha256:createHash('sha256').update(source).digest('hex')});
}
writeFileSync(`${evidence}/http-browser-proof.json`,JSON.stringify({results,wire,assets},null,2));
console.log(results);
