// Synthetic production-renderer smoke. Requires Playwright; no API/auth runtime.
import {createServer} from 'node:http';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const require=createRequire(import.meta.url);
const {chromium}=require(process.env.PLAYWRIGHT_MODULE??'playwright');
const output=resolve(process.argv[2]??'browser-evidence');
await mkdir(output,{recursive:true});
const staticRoot=new URL('../../src/main/resources/static/obs/',import.meta.url);
const server=createServer(async(req,res)=>{
 try {
  if(req.url==='/') {res.setHeader('Content-Type','text/html; charset=utf-8');res.end('<link rel="stylesheet" href="/obs.css"><main id="feed"></main>');return;}
  const name=req.url.slice(1);
  if(!['feed.mjs','renderer.mjs','colors.mjs','obs.css'].includes(name)){res.writeHead(404);res.end();return;}
  res.setHeader('Content-Type',name.endsWith('.css')?'text/css':'text/javascript');
  res.end(await readFile(new URL(name,staticRoot)));
 }catch{res.writeHead(500);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
let browser;
try {
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH??'/usr/bin/chromium',args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:1280,height:720},reducedMotion:'reduce'});
 const errors=[];page.on('pageerror',error=>errors.push(error.message));
 await page.goto(`http://127.0.0.1:${server.address().port}/`);
 const rows=await page.evaluate(async()=>{
  const {Renderer}=await import('/renderer.mjs'),{defaults}=await import('/feed.mjs');
  const match={teams:[
   {teamId:'attack',participants:[{participantId:'a',number:17,lastName:'Атакующий',firstName:'Алексей'},
    {participantId:'scorer',number:8,lastName:'Голевой',firstName:'Глеб'}]},
   {teamId:'defense',participants:[{participantId:'d',number:42,lastName:'Защитник',firstName:'Денис'},
    {participantId:'unknown',kind:'UNKNOWN'}]}
  ]};
  const types=['BLOCK','BLOCK_MARKER','BLOCK_FIELD','INTERCEPTION','CALLAHAN','GOAL'];
  const events=types.map(type=>({id:type,type,fromParticipantId:'a',toParticipantId:type==='GOAL'?'scorer':'d'}));
  events.push({id:'UNKNOWN',type:'INTERCEPTION',fromParticipantId:'a',toParticipantId:'unknown'},
   {id:'MISSING',type:'BLOCK',fromParticipantId:'missing',toParticipantId:'d'});
  new Renderer(document.querySelector('#feed')).render(events.map(event=>({event})),match,
   {...defaults,teamColors:{attack:'#ffcc00',defense:'#00ccff'}});
  return [...document.querySelectorAll('.feed-row')].map(row=>{
   const left=row.querySelector('.left'),right=row.querySelector('.right');
   return {id:row.dataset.eventId,left:left.textContent,right:right.textContent,
    leftColor:getComputedStyle(left).borderLeftColor,rightColor:getComputedStyle(right).borderRightColor,
    leftX:left.getBoundingClientRect().x,rightX:right.getBoundingClientRect().x,
    icon:row.querySelector('svg').getAttribute('aria-label')};
  });
 });
 assert.equal(rows.length,8);
 for(const row of rows){
  assert.equal(row.left,row.id==='MISSING'?'Неизвестный игрок':'17 Атакующий А.');
  assert.equal(row.right,row.id==='GOAL'?'8 Голевой Г.':row.id==='UNKNOWN'?'Неизвестный игрок':'42 Защитник Д.');
  assert.equal(row.leftColor,row.id==='MISSING'?'rgb(83, 199, 237)':'rgb(255, 204, 0)');
  assert.equal(row.rightColor,row.id==='GOAL'?'rgb(255, 204, 0)':'rgb(0, 204, 255)');
  assert.ok(row.leftX<row.rightX);
 }
 assert.deepEqual(errors,[]);
 await page.screenshot({path:resolve(output,'renderer.png')});
 await writeFile(resolve(output,'browser.json'),JSON.stringify({browser:browser.version(),rows,errors,nativeObs:false},null,2));
 console.log(`PASS: ${rows.length} actual Chromium rows, labels, order, accents; native OBS not exercised`);
}finally{await browser?.close();await new Promise(resolve=>server.close(resolve));}
