// Actual Chromium + production static modules; synthetic HTTP responses, not backend enforcement proof.
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
import {defaults} from '../../src/main/resources/static/obs/feed.mjs';
const require=createRequire(import.meta.url);
const {chromium}=require(process.env.PLAYWRIGHT_MODULE??'/home/mihanizzm/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root=new URL('../../src/main/resources/static/',import.meta.url);
const id='10000000-0000-0000-0000-000000000001',account='20000000-0000-0000-0000-000000000001',session='30000000-0000-0000-0000-000000000001';
const teamIds=['40000000-0000-0000-0000-000000000001','40000000-0000-0000-0000-000000000002'];
const teams=teamIds.map((teamId,index)=>({teamId,teamName:`Synthetic ${index}`,photoUrl:null,score:0,participants:[]}));
const match={id,teams,status:'PLANNED',startedAt:null,endedAt:null};
const context={contractVersion:'1.0.0',installationId:id,state:'AUTHENTICATED',sessionId:session,account:{id:account},permissions:['OBS_ADMIN','API_DOCS']};
let profile={shortNames:{},teamColors:{},swapped:false},config={...defaults},writes=[],requests=[];
const server=createServer(async(request,response)=>{
 try{
  const url=new URL(request.url,'http://localhost');requests.push({path:url.pathname,method:request.method,headers:request.headers});
  let body='';for await(const chunk of request)body+=chunk;
  const json=(value,status=200)=>{response.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store'});response.end(JSON.stringify(value));};
  if(url.pathname==='/favicon.ico'){response.writeHead(204);return response.end();}
  if(request.method==='PUT'||request.method==='POST'){
   writes.push({path:url.pathname,body,headers:request.headers});
   assert.equal(request.headers['x-ultistats-installation-id'],id);assert.equal(request.headers['x-ultistats-account-id'],account);assert.equal(request.headers['x-ultistats-session-id'],session);assert.equal(request.headers['x-csrf-token'],'component-masked-token');
   if(url.pathname==='/api/v1/obs/config'){config=JSON.parse(body);return json(config);}
   if(url.pathname.endsWith('/profile')){profile=JSON.parse(body);return json(profile);}
   return json({saved:true});
  }
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
  if(url.pathname==='/v3/api-docs')return json({openapi:'3.0.3',info:{title:'Component fixture',version:'1'},paths:{'/api/v1/teams':{post:{summary:'Synthetic create',requestBody:{required:true,content:{'application/json':{schema:{type:'object',properties:{name:{type:'string'}}}}}},responses:{'200':{description:'Saved'}}}}}});
  let bytes;
  if(url.pathname.startsWith('/swagger-ui/')&&url.pathname!=='/swagger-ui/index.html'){
   const name=url.pathname.slice('/swagger-ui/'.length);assert.ok(['swagger-ui.css','swagger-ui-bundle.js','swagger-ui-standalone-preset.js'].includes(name));
   bytes=await readFile(`${process.env.AUTH153_SWAGGER_ASSETS??'/tmp/ultistats-auth153/browser-assets'}/${name}`);
  }else{
   const path=url.pathname==='/swagger-ui/index.html'?'/auth/swagger.html':url.pathname;
   const source=new URL('.'+path,root);assert.ok(fileURLToPath(source).startsWith(fileURLToPath(root)));bytes=await readFile(source);
  }
  response.writeHead(200,{'Content-Type':url.pathname.endsWith('.html')?'text/html':url.pathname.endsWith('.css')?'text/css':'text/javascript'});response.end(bytes);
 }catch(error){response.writeHead(500);response.end('Component fixture failure');process.stderr.write(error.message+'\n');}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const origin=`http://127.0.0.1:${server.address().port}`;
const browser=await chromium.launch({executablePath:'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
const errors=[];
try{
 const staff=await browser.newContext();const page=await staff.newPage();page.on('pageerror',error=>errors.push(error.message));
 await page.goto(origin+'/obs-admin.html');await page.locator('#save').waitFor({state:'visible'});
 await page.waitForFunction(()=>!document.getElementById('save').disabled);
 await page.locator('#maxRows').fill('7');await page.locator('#save').click();await page.getByText('Сохранено для всех OBS-источников.',{exact:true}).waitFor();
 assert.equal(writes.length,1);assert.equal(JSON.parse(writes[0].body).maxRows,7);
 await page.locator('#match-id').fill(id);await page.locator('#open-match').click();
 const name=page.locator('[data-scoreboard-name]').first();await name.waitFor();await name.fill('Draft');
 await page.locator('#scoreboard-save').click();await page.getByText('Профиль сохранён для всех источников этого матча.',{exact:true}).waitFor();
 assert.equal(writes.length,2);assert.equal(JSON.parse(writes[1].body).shortNames[teamIds[0]],'Draft');
 context.sessionId='30000000-0000-0000-0000-000000000002';
 await name.fill('Unsaved original owner');await page.locator('#scoreboard-save').click();
 await page.waitForFunction(()=>document.getElementById('scoreboard-status').textContent.includes('Сессия изменилась'));
 assert.equal(writes.length,2);assert.equal(await name.inputValue(),'Unsaved original owner');
 context.sessionId=session;
 const docs=await staff.newPage();docs.on('pageerror',error=>errors.push(error.message));await docs.goto(origin+'/swagger-ui/index.html');
 await docs.locator('.opblock').waitFor();await docs.locator('.opblock-summary').click();await docs.getByRole('button',{name:'Try it out'}).click();
 await docs.locator('textarea.body-param__text').fill('{"name":"Synthetic browser create"}');
 const actualResponse=docs.waitForResponse(response=>response.request().method()==='POST'&&new URL(response.url()).pathname==='/api/v1/teams');
 await docs.getByRole('button',{name:'Execute',exact:true}).click();assert.equal((await actualResponse).status(),200);
 assert.equal(writes.length,3);assert.equal(writes[2].path,'/api/v1/teams');
 await staff.close();requests=[];
 const native=await browser.newContext();const source=await native.newPage();source.on('pageerror',error=>errors.push(error.message));
 await source.goto(origin+`/obs-scoreboard.html?matchId=${id}`);await source.locator('.scoreboard-score').first().waitFor();
 assert.equal(requests.some(request=>request.path.startsWith('/api/v1/auth/')||request.path.startsWith('/auth/')),false,'Public source must not import staff adapter or fetch auth context');
 assert.equal(requests.some(request=>request.headers.cookie||request.headers.authorization),false,'Fresh public source has no staff credential');
 assert.deepEqual(errors,[]);await native.close();
 console.log(JSON.stringify({status:'PASS',scope:'Chromium component with synthetic HTTP responses; no real backend authorization/PG/native OBS claim',checks:['global save captured headers','scoreboard save captured headers','changed owner stops save and preserves draft','actual Swagger5.21 try-it-out interceptor','fresh public source without staff imports/auth requests/credentials'],writes:writes.length,errors:0}));
}finally{await browser.close();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
