// Engineering integration: owned PG/Caddy HTTPS, genuine frozen helper, real UI/API; no HAR/trace.
import assert from 'node:assert/strict';
import {readFileSync,writeFileSync,renameSync,unlinkSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';
const input=JSON.parse(readFileSync(0,'utf8')), {origin,pg,match,other,evidence,spki}=input;
const {chromium}=await import(pathToFileURL(process.env.OBS_PLAYWRIGHT).href);
const browser=await chromium.launch({executablePath:'/usr/bin/chromium',headless:true,args:[`--ignore-certificate-errors-spki-list=${spki}`]});
const sql=text=>execFileSync('docker',['exec','-i',pg,'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','obs_fixture','-d','obs_fixture'],{input:text,encoding:'utf8'}).trim();
const results=[],wire=[],secrets=[],errors=[];
const keyPath='/api/v1/obs/source-keys';
try {
 const context=await browser.newContext({viewport:{width:1280,height:1000},permissions:['clipboard-read','clipboard-write']});
 await context.addCookies(Object.entries(input.cookies).map(([name,value])=>({name,value,url:origin,secure:true})));
 const page=await context.newPage();page.on('pageerror',()=>errors.push('pageerror'));
 page.on('request',async request=>{
  const url=new URL(request.url());assert.equal(url.hash,'');assert.ok(!url.search.includes('key='));
  if(!url.pathname.startsWith(keyPath)&&!url.pathname.startsWith('/api/v1/obs/previews/'))return;
  const headers=await request.allHeaders().catch(()=>null);if(!headers)return;
  assert.ok(headers.cookie&&headers['x-ultistats-session-id']);assert.equal(headers.authorization,undefined);
  if(request.method()!=='GET')assert.ok(headers['x-csrf-token']&&headers['x-ultistats-account-id']&&headers['x-ultistats-installation-id']);
  wire.push({path:url.pathname,method:request.method(),capturedIdentity:true,csrf:request.method()==='GET'||!!headers['x-csrf-token']});
 });
 const status=kind=>page.locator(`#link-${kind}-status`);
 const field=kind=>page.locator(`#link-${kind}-url`);
 const settled=async()=>{await page.waitForFunction(()=>['scoreboard','event-log'].every(k=>document.querySelector(`#link-${k}-status`).textContent&&document.querySelector(`#link-${k}-status`).textContent!=='Проверка текущей ссылки…'));};
 const issue=async kind=>{
  await page.locator(`#link-${kind}-issue`).click();
  const response=page.waitForResponse(r=>r.url().endsWith(keyPath)&&r.request().method()==='POST');
  await page.locator('#links-confirm').click();const r=await response;assert.equal(r.status(),201);const data=await r.json();
  secrets.push(data.url);await page.waitForFunction(k=>document.querySelector(`#link-${k}-status`).textContent==='Активная ссылка'&&!document.querySelector(`#link-${k}-issue`).disabled,kind);return data;
 };
 const viewer=async(data,expected)=>{
  const url=new URL(data.url),kind=data.key.scope.endsWith('SCOREBOARD')?'scoreboard':'event-log';
  const r=await context.request.get(`${origin}/api/v1/obs/sources/${kind}/${data.key.matchId??'auto'}`,{headers:{Authorization:'Bearer '+url.hash.slice(5)}});
  assert.equal(r.status(),expected);return expected===200?r.json():null;
 };
 await page.goto(origin+'/obs-admin.html');
 await page.locator('#active-links > summary').click();
 await page.locator('#log-settings > summary').click();
 await page.locator('#scoreboard-settings > summary').click();
 await settled();
 assert.equal(await field('scoreboard').inputValue(),'');assert.equal(wire.filter(r=>r.method==='POST').length,0);
 const autoScore=await issue('scoreboard'),autoLog=await issue('event-log');
 assert.equal((await viewer(autoScore,200)).snapshot,null);assert.equal((await viewer(autoLog,200)).snapshot,null);
 results.push('AUTO links remain ACTIVE/copyable with real snapshot:null; two distinct scopes');
 await page.locator('#link-scoreboard-copy').click();
 assert.ok(await page.evaluate(url=>navigator.clipboard.readText().then(v=>v===url),autoScore.url));
 results.push('genuine clipboard current copy boolean assertion only');
 await page.reload();await settled();assert.ok((await field('scoreboard').inputValue())===autoScore.url);
 assert.equal(wire.filter(r=>r.method==='POST').length,2);results.push('reopen restores current without POST');
 // Real helper network request committed, response intentionally lost: same-operation retry must replay.
 let firstOperation;
 await page.route('**/api/v1/obs/source-keys',async route=>{
  if(route.request().method()!=='POST')return route.continue();
  firstOperation=route.request().postDataJSON().operationId;const r=await route.fetch();assert.equal(r.status(),201);await route.abort('failed');
 },{times:1});
 await page.locator('#link-scoreboard-issue').click();await page.locator('#links-confirm').click();
 await page.waitForFunction(()=>document.querySelector('#link-scoreboard-status').textContent.includes('неизвестен'));
 const retryRequest=page.waitForRequest(r=>r.method()==='POST'&&r.url().endsWith(keyPath));
 const rotated=await issue('scoreboard');assert.equal((await retryRequest).postDataJSON().operationId,firstOperation);
 await viewer(autoScore,401);await viewer(rotated,200);await viewer(autoLog,200);
 assert.ok((await field('event-log').inputValue())===autoLog.url);
 results.push('real committed-but-lost POST then same-operation UI retry: old viewer401/new200, other binding preserved');
 // Explicit editor target remains match while new source links and server AUTO select another match.
 await page.locator('#links-mode').selectOption('exact');await page.locator('#links-match').fill(match);await page.locator('#links-open').click();await settled();
 await page.waitForFunction(()=>!document.querySelector('#scoreboard-fields').disabled);
 await page.locator('[data-scoreboard-name]').first().fill('F2 draft');
 sql(`UPDATE matches SET started_at=now() WHERE id='${other}';`);
 assert.equal((await viewer(rotated,200)).snapshot.matchId,other);
 const save=page.waitForResponse(r=>r.request().method()==='PUT'&&r.url().endsWith(`/api/v1/obs/scoreboard/${match}/profile`));
 await page.locator('#scoreboard-form button[type=submit]').click();assert.equal((await save).status(),200);
 const profile=await context.request.get(`${origin}/api/v1/obs/scoreboard/${match}`,{headers:input.staff});
 assert.equal(profile.status(),200);assert.ok(Object.values((await profile.json()).profile.shortNames).includes('F2 draft'));
 assert.equal(await page.locator('#editor-context').getAttribute('data-match-id'),match);
 const configSave=page.waitForResponse(r=>r.request().method()==='PUT'&&new URL(r.url()).pathname==='/api/v1/obs/config');
 await page.locator('#maxRows').fill('4');await page.locator('#save').click();assert.equal((await configSave).status(),200);
 results.push('AUTO server switch leaves explicit scoreboard editor target/draft/save on opened match; global Event Log config still saves');
 await page.locator('#links-mode').selectOption('exact');await page.locator('#links-match').fill(match);await page.locator('#links-open').click();await settled();
 const exactScore=await issue('scoreboard'),exactLog=await issue('event-log');
 assert.equal(exactScore.key.scope,'EXACT_SCOREBOARD');assert.equal(exactLog.key.scope,'EXACT_EVENT_LOG');
 await viewer(exactScore,200);await viewer(exactLog,200);await viewer(autoLog,200);
 for(const kind of ['scoreboard','event-log']) {
  const preview=page.waitForResponse(r=>r.url().includes(`/api/v1/obs/previews/${kind}/${match}`)&&r.status()===200);
  await page.locator('#links-kind').selectOption(kind);if(kind==='scoreboard')await page.locator('#links-preview').evaluate(el=>el.contentWindow.location.reload());await preview;
  assert.ok((await page.locator('#links-preview').getAttribute('src'))===`/obs-preview/${kind}.html?matchId=${match}`);
 }
 results.push('four real scoped bindings and genuine F1 scoreboard/event-log keyless iframes');
 await page.locator('#link-scoreboard-revoke').click();
 const remote=await context.request.post(origin+keyPath,{headers:input.staff,data:{operationId:crypto.randomUUID(),scope:'EXACT_SCOREBOARD',matchId:match}});
 assert.equal(remote.status(),201);const replacement=await remote.json();secrets.push(replacement.url);
 const oldDelete=page.waitForResponse(r=>r.request().method()==='DELETE'&&r.url().endsWith('/'+exactScore.key.keyId));
 await page.locator('#links-confirm').click();assert.equal((await oldDelete).status(),204);
 await page.waitForFunction(()=>document.querySelector('#link-scoreboard-status').textContent==='Активная ссылка'&&!document.querySelector('#link-scoreboard-issue').disabled);
 assert.ok((await field('scoreboard').inputValue())===replacement.url);await viewer(replacement,200);
 results.push('real another-tab rotate before captured old-key DELETE: refreshed current preserves replacement ACTIVE');

 // Cancel and context switch cannot retarget an open revoke confirmation.
 await page.locator('#link-scoreboard-revoke').click();await page.locator('#links-cancel').click();await viewer(replacement,200);
 await page.locator('#link-scoreboard-revoke').click();
 // UI modal deliberately prevents background selection; programmatic event exercises context-close guard.
 await page.evaluate(()=>{document.querySelector('#links-mode').value='auto';document.querySelector('#links-mode').dispatchEvent(new Event('change'));});await settled();
 assert.equal(await page.locator('#links-dialog').evaluate(el=>el.open),false);await viewer(replacement,200);
 await page.locator('#link-scoreboard-revoke').click();const deleted=page.waitForResponse(r=>r.request().method()==='DELETE'&&r.url().endsWith('/'+rotated.key.keyId));await page.locator('#links-confirm').click();assert.equal((await deleted).status(),204);
 await page.waitForFunction(()=>document.querySelector('#link-scoreboard-status').textContent==='Активной ссылки нет');await viewer(rotated,401);await viewer(autoLog,200);
 results.push('captured chosen-key revoke only after confirmation; canceled/switched dialog no revoke; other scope preserved');
 sql(`UPDATE obs_source_keys SET operation_id=NULL,derivation_version=NULL WHERE key_id='${autoLog.key.keyId}';`);
 const writes=wire.filter(r=>r.method!=='GET').length;await page.locator('#links-refresh').click();await settled();
 assert.ok((await status('event-log').textContent()).includes('Старая ссылка'));assert.equal(await field('event-log').inputValue(),'');
 assert.equal(wire.filter(r=>r.method!=='GET').length,writes);results.push('real legacy digest-only REISSUE_REQUIRED does not issue or revoke automatically');
 renameSync(input.keyFile,input.keyFile+'.saved');try {
  writeFileSync(input.keyFile,Buffer.alloc(32,7),{mode:0o400});
  await page.locator('#links-mode').selectOption('exact');await page.locator('#links-match').fill(match);await page.locator('#links-open').click();await settled();
  assert.ok((await status('scoreboard').textContent()).includes('503'));assert.equal(await field('scoreboard').inputValue(),'');assert.equal(wire.filter(r=>r.method!=='GET').length,writes);
 }finally{unlinkSync(input.keyFile);renameSync(input.keyFile+'.saved',input.keyFile);}
 results.push('actual unavailable owned key material503 disables copy with no mutation');
 await page.locator('#links-refresh').click();await settled();
 await page.screenshot({path:evidence+'/active-links-masked.png',fullPage:true,mask:[field('scoreboard'),field('event-log')]});
 // Replacement session notice must invalidate both parent links and the child preview without adopting it.
 await context.addCookies(Object.entries(input.replacement).map(([name,value])=>({name,value,url:origin,secure:true})));
 await page.evaluate(()=>{const c=new BroadcastChannel('ultistats-auth-session-v1');c.postMessage({type:'session-changed'});c.close();});
 await page.waitForFunction(()=>document.querySelector('#link-scoreboard-issue').disabled&&document.querySelector('#link-scoreboard-url').value==='');
 assert.equal(await page.locator('#links-preview').getAttribute('src'),'about:blank');assert.ok(await page.locator('#scoreboard-fields').evaluate(el=>el.disabled));
 results.push('genuine helper session change clears private fields, disables editor/link writes, stops keyless iframe');
 assert.ok(await page.evaluate(values=>[localStorage,sessionStorage].every(storage=>Object.values(storage).every(value=>values.every(secret=>!value.includes(secret)))),secrets));
 await page.evaluate(()=>dispatchEvent(new Event('pagehide')));await context.close();assert.deepEqual(errors,[]);
 writeFileSync(evidence+'/browser-results.json',JSON.stringify({results,wire,screenshot:'active-links-masked.png: both URL fields masked',limits:'Synthetic engineering integration; intentional lost-response route only around real committed POST; Node races separate; no native OBS/CEF/ProductQA/hosted.'},null,2)+'\n');
 console.log(`PASS ${results.length} actual F2 integration scenarios`);
}finally{await browser.close();}
