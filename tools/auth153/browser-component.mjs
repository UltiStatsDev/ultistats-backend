// Actual Chromium + production static modules; synthetic HTTP responses, not backend enforcement proof.
import {createHash} from 'node:crypto';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
import {defaults} from '../../src/main/resources/static/obs/feed.mjs';
const require=createRequire(import.meta.url);
const {chromium}=require(process.env.PLAYWRIGHT_MODULE??'/home/mihanizzm/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const helperPath=process.env.AUTH153_STAFF_HELPER??'/tmp/ultistats-auth153/shared-helper-59588da-canonical/staff-auth-v1.mjs';
const helperBytes=await readFile(helperPath),helperSha=createHash('sha256').update(helperBytes).digest('hex');
const helperManifest=JSON.parse(await readFile(process.env.AUTH153_STAFF_MANIFEST??new URL('manifest.json','file://'+helperPath),'utf8'));
assert.equal(helperManifest.files[0].sha256,helperSha);assert.equal(helperManifest.files[0].bytes,helperBytes.length);
const helper={webSourceCommit:helperManifest.webSourceCommit,sha256:helperSha,bytes:helperBytes.length};
const root=new URL('../../src/main/resources/static/',import.meta.url);
const id='10000000-0000-0000-0000-000000000001',account='20000000-0000-0000-0000-000000000001',session='30000000-0000-0000-0000-000000000001';
const teamIds=['40000000-0000-0000-0000-000000000001','40000000-0000-0000-0000-000000000002'];
const teams=teamIds.map((teamId,index)=>({teamId,teamName:`Synthetic ${index}`,photoUrl:null,score:0,participants:[]}));
const match={id,teams,status:'PLANNED',startedAt:null,endedAt:null};
const context={serverNow:new Date().toISOString(),lastActivityAt:new Date().toISOString(),idleExpiresAt:'2026-11-15T00:00:00Z',reauthenticatedUntil:null,replacedSessionId:null,contractVersion:'1.0.0',installationId:id,state:'AUTHENTICATED',sessionId:session,account:{id:account,login:'organizer',displayName:'Organizer',role:'ORGANIZER',status:'ACTIVE',mustChangePassword:false,createdAt:'2026-11-01T00:00:00Z',updatedAt:'2026-11-01T00:00:00Z'},permissions:['SPORTS_WRITE','STATISTICS_READ_ALL','TRANSFER','OBS_ADMIN','API_DOCS']};
let deferredProfile=null;
let profile={shortNames:{},teamColors:{},swapped:false},config={...defaults},writes=[],requests=[];
const server=createServer(async(request,response)=>{
 try{
  const url=new URL(request.url,'http://localhost');requests.push({path:url.pathname,method:request.method,headers:request.headers});
  let body='';for await(const chunk of request)body+=chunk;
  const json=(value,status=200)=>{response.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store'});response.end(JSON.stringify(value));};
  if(url.pathname==='/favicon.ico'){response.writeHead(204);return response.end();}
  if(url.pathname==='/api/v1/auth/renew')return json(context);
  if(url.pathname==='/api/v1/auth/logout')return json({code:'SERVICE_UNAVAILABLE'},503);
  if(request.method==='PUT'||request.method==='POST'){
   writes.push({path:url.pathname,body,headers:request.headers});
   assert.equal(request.headers['x-ultistats-installation-id'],id);assert.equal(request.headers['x-ultistats-account-id'],account);assert.equal(request.headers['x-ultistats-session-id'],session);assert.equal(request.headers['x-csrf-token'],'component-masked-token');
   if(url.pathname==='/api/v1/obs/config'){config=JSON.parse(body);return json(config);}
   if(url.pathname.endsWith('/profile')){profile=JSON.parse(body);if(deferredProfile){deferredProfile.committed.resolve();await deferredProfile.release.promise;}return json(profile);}
   return json({saved:true});
  }
  if(url.pathname==='/api/v1/installation')return json({contractVersion:'1.0.0',installationId:id,entryMode:'LOOPBACK',publicOrigin:'http://localhost:8080',secureOrigin:origin});
  if(url.pathname==='/api/v1/auth/session')return json(context);
  if(url.pathname==='/api/v1/auth/csrf')return json({headerName:'X-CSRF-TOKEN',token:'component-masked-token'});
  if(url.pathname==='/api/v1/obs/stream'){
   response.writeHead(200,{'Content-Type':'text/event-stream','Cache-Control':'no-store'});response.write('event: connected\ndata: {}\n\n');return;
  }
  if(url.pathname==='/api/v1/obs/config')return json(config);
  if(url.pathname==='/api/v1/matches')return json({content:[match],totalPages:1});
  if(url.pathname===`/api/v1/matches/${id}`)return json(match);
  if(url.pathname===`/api/v2/matches/${id}/events`)return json({events:[],lastSequenceNumber:0,logRevision:0});
  if(url.pathname===`/api/v1/obs/scoreboard/${id}`)return json({matchId:id,teams,status:'PLANNED',startedAt:null,endedAt:null,serverNow:new Date().toISOString(),profile});
  if(url.pathname==='/v3/api-docs')return json({openapi:'3.0.3',info:{title:'Component fixture',version:'1'},paths:{...Object.fromEntries(['/api/v1/teams','/api/v1/auth/login','/api/v1/accounts'].map(path=>[path,{post:{summary:path,requestBody:{required:true,content:{'application/json':{schema:{type:'object',properties:{name:{type:'string'}}}}}},responses:{'200':{description:'Saved'}}}}])),'/api/v1/teams/photo':{put:{summary:'Multipart fixture',requestBody:{required:true,content:{'multipart/form-data':{schema:{type:'object',properties:{file:{type:'string',format:'binary'}}}}}},responses:{'200':{description:'Saved'}}}}}});
  let bytes;
  if(url.pathname==='/staff-auth-v1.mjs'){
   bytes=helperBytes;
  }else if(url.pathname.startsWith('/swagger-ui/')&&url.pathname!=='/swagger-ui/index.html'){
   const name=url.pathname.slice('/swagger-ui/'.length);assert.ok(['swagger-ui.css','swagger-ui-bundle.js','swagger-ui-standalone-preset.js'].includes(name));
   bytes=await readFile(`${process.env.AUTH153_SWAGGER_ASSETS??'/tmp/ultistats-auth153/browser-assets'}/${name}`);
  }else{
   const path=url.pathname==='/swagger-ui/index.html'?'/auth/swagger.html':url.pathname;
   const source=new URL('.'+path,root);assert.ok(fileURLToPath(source).startsWith(fileURLToPath(root)));bytes=await readFile(source);
  }
  response.writeHead(200,{'Cache-Control':'no-store','Content-Type':url.pathname.endsWith('.html')?'text/html':url.pathname.endsWith('.css')?'text/css':'text/javascript'});response.end(bytes);
 }catch(error){response.writeHead(500);response.end('Component fixture failure');process.stderr.write(error.message+'\n');}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const origin=`http://127.0.0.1:${server.address().port}`;
const browser=await chromium.launch({executablePath:'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
const errors=[];
try{
 const staff=await browser.newContext();staff.setDefaultTimeout(10000);const page=await staff.newPage();page.on('pageerror',error=>{errors.push(error.message);console.error('PAGEERROR',error.message);});page.on('console',msg=>{if(msg.type()==='error')console.error('CONSOLE',msg.text());});
 await page.goto(origin+'/obs-admin.html');await page.locator('#save').waitFor({state:'visible'});
 await page.waitForFunction(()=>!document.getElementById('save').disabled,{},{timeout:10000}).catch(async error=>{console.error('STATUS',await page.locator('#auth-status').textContent(),requests.map(x=>x.path));throw error;});
 assert.equal(requests.some(request=>request.path==='/api/v1/auth/renew'),false,'Passive bootstrap/SSE does not renew');
 await page.locator('#maxRows').fill('7');await page.locator('#save').click();await page.getByText('Сохранено для всех OBS-источников.',{exact:true}).waitFor();
 assert.equal(writes.length,1);assert.equal(JSON.parse(writes[0].body).maxRows,7);
 await page.locator('#match-id').fill(id);await page.locator('#open-match').click();
 await page.waitForFunction(()=>document.getElementById('obs-url').value.startsWith('http://localhost:8080/'));
 assert.equal(new URL(await page.locator('#obs-url').inputValue()).origin,'http://localhost:8080');
 assert.equal(new URL(await page.locator('#scoreboard-url').inputValue()).origin,'http://localhost:8080');
 assert.equal(new URL(await page.locator('#preview').getAttribute('src'),origin).origin,origin);
 assert.equal(new URL(await page.locator('#scoreboard-preview').getAttribute('src'),origin).origin,origin);
 const name=page.locator('[data-scoreboard-name]').first();await name.waitFor();await name.fill('Draft');
 await page.locator('#scoreboard-save').click();await page.getByText('Профиль сохранён для всех источников этого матча.',{exact:true}).waitFor();
 assert.equal(writes.length,2);assert.equal(JSON.parse(writes[1].body).shortNames[teamIds[0]],'Draft');
 context.sessionId='30000000-0000-0000-0000-000000000002';
 await name.fill('Unsaved original owner');await page.locator('#scoreboard-save').click();
 await page.waitForFunction(()=>document.getElementById('scoreboard-status').textContent.includes('Результат сохранения не подтверждён')||document.getElementById('scoreboard-status').textContent.includes('Сессия изменилась'));
 assert.equal(writes.length,2);assert.equal(await name.inputValue(),'Unsaved original owner');
 context.sessionId=session;
 const docs=await staff.newPage();docs.on('pageerror',error=>errors.push(error.message));await docs.goto(origin+'/swagger-ui/index.html');
 await docs.locator('.opblock').first().waitFor();
 for(const path of ['/api/v1/auth/login','/api/v1/accounts']){
  const operation=docs.locator('.opblock').filter({has:docs.locator(`.opblock-summary-path[data-path="${path}"]`)});
  await operation.locator('.opblock-summary').click();assert.equal(await operation.getByRole('button',{name:'Try it out'}).count(),0);
 }
 const operation=docs.locator('.opblock').filter({has:docs.locator('.opblock-summary-path[data-path="/api/v1/teams"]')});
 await operation.locator('.opblock-summary').click();await operation.getByRole('button',{name:'Try it out'}).click();
 await docs.locator('textarea.body-param__text').fill('{"name":"Synthetic browser create"}');
 const actualResponse=docs.waitForResponse(response=>response.request().method()==='POST'&&new URL(response.url()).pathname==='/api/v1/teams');
 await docs.getByRole('button',{name:'Execute',exact:true}).click();assert.equal((await actualResponse).status(),200);
 assert.equal(writes.length,3);assert.equal(writes[2].path,'/api/v1/teams');
 await docs.waitForFunction(()=>document.querySelector('.responses-wrapper').textContent.includes('saved'));
 assert.equal(JSON.stringify(await docs.evaluate(()=>ui.getSystem().getState().toJS())).includes('component-masked-token'),false,'Swagger Redux must not contain CSRF');
 const upload=docs.locator('.opblock').filter({has:docs.locator('.opblock-summary-path[data-path="/api/v1/teams/photo"]')});
 await upload.locator('.opblock-summary').click();await upload.getByRole('button',{name:'Try it out'}).click();
 await upload.locator('input[type=file]').setInputFiles({name:'fixture.txt',mimeType:'text/plain',buffer:Buffer.from('synthetic-multipart-content')});
 const multipartResponse=docs.waitForResponse(response=>response.request().method()==='PUT'&&new URL(response.url()).pathname==='/api/v1/teams/photo');
 await upload.getByRole('button',{name:'Execute',exact:true}).click();assert.equal((await multipartResponse).status(),200);
 assert.equal(writes.length,4);assert.match(writes[3].headers['content-type'],/^multipart\/form-data; boundary=/);
 assert.match(writes[3].body,/filename="fixture.txt"/);assert.match(writes[3].body,/synthetic-multipart-content/);
 assert.equal(JSON.stringify(await docs.evaluate(()=>ui.getSystem().getState().toJS())).includes('component-masked-token'),false);
 assert.equal(requests.filter(request=>request.path==='/api/v1/auth/renew').length,1,'Trusted activity is coalesced across OBS and Swagger tabs');
 context.sessionId='30000000-0000-0000-0000-000000000002';
 await page.evaluate(async()=>{const {BROWSER_AUTH_PROTOCOL:p}=await import('/staff-auth-v1.mjs');const channel=new BroadcastChannel(p.noticeChannel);channel.postMessage({type:p.noticeType});channel.close();});
 await docs.waitForFunction(()=>document.getElementById('auth-status').textContent.includes('Сессия изменилась'));
 assert.equal(await operation.getByRole('button',{name:'Execute',exact:true}).isDisabled(),true);
 assert.equal(await upload.getByRole('button',{name:'Execute',exact:true}).isDisabled(),true);
 assert.equal(await docs.locator('textarea.body-param__text').inputValue(),'{"name":"Synthetic browser create"}');
 assert.equal(writes.length,4);context.sessionId=session;
 // Seed the producer's documented UUID-only durable intent, then cold-bootstrap.
 await docs.evaluate(async({installationId,accountId,sessionId})=>{
  const {BROWSER_AUTH_PROTOCOL:p}=await import('/staff-auth-v1.mjs');
  await new Promise((resolve,reject)=>{const open=indexedDB.open(p.database,p.databaseVersion);open.onerror=()=>reject(open.error);open.onsuccess=()=>{
   const db=open.result,tx=db.transaction([p.intentStore,p.expectedStore],'readwrite');
   const identity={owner:{installationId,accountId},sessionId};
   tx.objectStore(p.intentStore).put(identity,JSON.stringify([installationId,sessionId]));tx.objectStore(p.expectedStore).put(identity,installationId);
   tx.oncomplete=()=>{db.close();resolve();};tx.onerror=()=>reject(tx.error);
  };});
 },{installationId:id,accountId:account,sessionId:session});
 await docs.reload();await docs.waitForFunction(()=>document.getElementById('auth-status').textContent.includes('Войдите'));
 assert.equal(await docs.locator('.opblock').count(),0,'Durable pending logout blocks cold Swagger bootstrap');
 assert.equal(writes.length,4);
 await staff.close();
 const lateContext=await browser.newContext();lateContext.setDefaultTimeout(10000);
 const late=await lateContext.newPage();late.on('pageerror',error=>errors.push(error.message));
 await late.goto(origin+'/obs-admin.html');await late.waitForFunction(()=>!document.getElementById('save').disabled);
 await late.locator('#match-id').fill(id);await late.locator('#open-match').click();
 const lateName=late.locator('[data-scoreboard-name]').first();await lateName.waitFor();await lateName.fill('Committed before invalidation');
 await late.evaluate(()=>{
  const element=document.getElementById('scoreboard-status'),descriptor=Object.getOwnPropertyDescriptor(Node.prototype,'textContent');
  globalThis.statusWrites=[];Object.defineProperty(element,'textContent',{get(){return descriptor.get.call(this);},set(value){globalThis.statusWrites.push(value);descriptor.set.call(this,value);}});
 });
 const beforeLateWrite=writes.length;
 deferredProfile={committed:Promise.withResolvers(),release:Promise.withResolvers()};
 await late.locator('#scoreboard-save').click();await deferredProfile.committed.promise;
 assert.equal(profile.shortNames[teamIds[0]],'Committed before invalidation','Server has committed before response is released');
 context.sessionId='30000000-0000-0000-0000-000000000002';
 await late.evaluate(async()=>{const {BROWSER_AUTH_PROTOCOL:p}=await import('/staff-auth-v1.mjs');const channel=new BroadcastChannel(p.noticeChannel);channel.postMessage({type:p.noticeType});channel.close();});
 await late.waitForFunction(()=>document.getElementById('auth-status').textContent.includes('Сессия изменилась'));
 deferredProfile.release.resolve();
 await late.waitForFunction(()=>globalThis.statusWrites.some(value=>/Результат сохранения не подтверждён|Не сохранено/.test(value)));
 const statusHistory=await late.evaluate(()=>globalThis.statusWrites);
 assert.equal(statusHistory.some(value=>value.startsWith('Не сохранено')),false,'A stale completion cannot claim that an already committed profile was not saved');
 assert.equal(statusHistory.some(value=>value.startsWith('Результат сохранения не подтверждён')),true);
 assert.equal(await lateName.inputValue(),'Committed before invalidation');assert.equal(await lateName.isDisabled(),true);
 assert.equal(writes.length,beforeLateWrite+1,'Uncertain completion is never retried');
 deferredProfile=null;context.sessionId=session;await lateContext.close();requests=[];
 const native=await browser.newContext();const source=await native.newPage();source.on('pageerror',error=>errors.push(error.message));
 await source.goto(origin+`/obs-scoreboard.html?matchId=${id}`);await source.locator('.scoreboard-score').first().waitFor();
 assert.equal(requests.some(request=>request.path.startsWith('/api/v1/auth/')||request.path.startsWith('/auth/')),false,'Public source must not import staff adapter or fetch auth context');
 assert.equal(requests.some(request=>request.headers.cookie||request.headers.authorization),false,'Fresh public source has no staff credential');
 assert.deepEqual(errors,[]);await native.close();
 console.log(JSON.stringify({status:'PASS',helper,scope:'Chromium component with synthetic HTTP responses; no real backend authorization/PG/native OBS claim',checks:['global save captured headers','scoreboard save captured headers','changed owner stops save and preserves draft','actual Swagger5.21 shared network transport, secret-free Redux, auth/accounts Try it out blocked','real BroadcastChannel invalidation blocks old Swagger actor and retains draft','durable pending logout prevents cold Swagger bootstrap','committed profile with stale withheld response reports unconfirmed outcome, retains draft and disabled controls, no retry','actual Swagger multipart file serialization and shared CSRF network transport','trusted activity coalesced across tabs, passive bootstrap/SSE does not renew','configured public source URLs with secure same-origin previews','fresh public source without staff imports/auth requests/credentials'],writes:writes.length,errors:0}));
}finally{await browser.close();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
