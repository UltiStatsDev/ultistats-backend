// Optional visual regression probe; use an existing Playwright installation.
// No browser libraries are added to the application or its runtime dependencies.
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
const assets=path.resolve(__dirname,'../../src/main/resources/static/obs');
const output=path.resolve(process.env.OBS141_OUTPUT||'/tmp/ultistats-ux141-browser');
const html=`<!doctype html><meta charset="utf-8"><title>UX141 renderer · 27px</title>
<link rel="stylesheet" href="/obs/obs.css"><style>
body{padding:24px;background:#354052;color:white}h1{font-size:18px}
#feed{position:static;max-width:100%;align-items:flex-start}.strip{display:flex;gap:40px;margin:24px 0}
.strip svg{width:27px;height:27px;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}
.strip div{display:grid;gap:8px}
</style><h1>UX141 · standalone / feed-row · 27 px</h1><div class="strip"></div><div id="feed"></div>
<script type="module">
import {Renderer} from '/obs/renderer.mjs';import {defaults} from '/obs/feed.mjs';
const types=['GOAL','BLOCK','BLOCK_MARKER','BLOCK_FIELD','INTERCEPTION','CALLAHAN'];
const match={teams:[{teamId:'a',participants:[{participantId:'thrower',number:17,lastName:'Константинопольский',firstName:'Александр'}]},
{teamId:'b',participants:[{participantId:'receiver',number:9,lastName:'Защитников',firstName:'Борис'},{participantId:'unknown',kind:'UNKNOWN'}]}]};
const rows=types.map(type=>({event:{id:type,type,fromParticipantId:'thrower',toParticipantId:'receiver'}}));
rows.push({event:{id:'unknown',type:'INTERCEPTION',fromParticipantId:'unknown'}});
window.renderer=new Renderer(document.querySelector('#feed'));window.rows=rows;window.match=match;window.config=defaults;
renderer.render(rows,match,defaults);
for(const type of ['GOAL','BLOCK','INTERCEPTION','CALLAHAN']){const svg=renderer.nodes.get(type).querySelector('svg').cloneNode(true);const box=document.createElement('div');box.append(svg);const label=document.createElement('small');label.textContent=svg.getAttribute('aria-label');box.append(label);document.querySelector('.strip').append(box);}
window.ready=true;
</script>`;
(async()=>{
 fs.mkdirSync(output,{recursive:true});
 const server=http.createServer((req,res)=>{
  const name=req.url.slice('/obs/'.length);
  if(req.url==='/'){res.setHeader('Content-Type','text/html');res.end(html);return;}
  if(!req.url.startsWith('/obs/')||!['renderer.mjs','feed.mjs','colors.mjs','obs.css'].includes(name)){res.writeHead(404).end();return;}
  res.setHeader('Content-Type',name.endsWith('.mjs')?'text/javascript':'text/css');res.end(fs.readFileSync(path.join(assets,name)));
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 let browser;
 try {
  browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
  const page=await browser.newPage({viewport:{width:1100,height:650},deviceScaleFactor:1});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto(`http://127.0.0.1:${server.address().port}`);await page.waitForFunction(()=>window.ready);
  await page.getByRole('img',{name:'Гол',exact:true}).last().waitFor();
  const result=await page.evaluate(()=>{
   const nodes=[...document.querySelectorAll('.feed-row')];
   const texts=nodes.map(n=>[n.children[0].textContent,n.children[2].textContent]);
   const icons=nodes.map(n=>{const s=n.querySelector('svg'),b=s.getBoundingClientRect();return {label:s.getAttribute('aria-label'),role:s.getAttribute('role'),title:s.querySelector('title').textContent,width:b.width,height:b.height};});
   const animations=nodes.map(n=>n.getAnimations()[0]);
   for(let i=0;i<10;i++)renderer.render(rows,match,config);
   return {texts,icons,stable:nodes.every((n,i)=>n===document.querySelectorAll('.feed-row')[i]),animationsStable:nodes.every((n,i)=>n.getAnimations()[0]===animations[i]),count:nodes.length};
  });
  assert.equal(result.count,7);assert.equal(result.stable,true);assert.equal(result.animationsStable,true);
  assert.deepEqual(result.texts[0],['17 Константинопольский А.','9 Защитников Б.']);
  for(const texts of result.texts.slice(1,6))assert.deepEqual(texts,['9 Защитников Б.','17 Константинопольский А.']);
  assert.deepEqual(result.texts[6],['Неизвестный игрок','Неизвестный игрок']);
  assert.deepEqual(result.icons.map(i=>i.label),['Гол','Блок','Блок','Блок','Перехват','Кэллахан','Перехват']);
  for(const icon of result.icons){assert.equal(icon.role,'img');assert.equal(icon.title,icon.label);assert.equal(icon.width,27);assert.equal(icon.height,27);}
  await page.emulateMedia({reducedMotion:'reduce'});
  await page.screenshot({path:path.join(output,'markers-dark-27px.png')});
  await page.setViewportSize({width:480,height:650});
  await page.evaluate(()=>renderer.render(rows,match,{...config,backgroundColor:'#f2f4f8',textColor:'#17202e'}));
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'long Cyrillic names do not overflow the viewport');
  await page.screenshot({path:path.join(output,'markers-narrow-light-27px.png')});
  // The API accepts opaque row colors. Transparency belongs to the page
  // outside the cards; remove only this probe's backdrop to inspect it.
  await page.addStyleTag({content:'body{background:transparent}'});
  assert.equal(await page.evaluate(()=>getComputedStyle(document.documentElement).backgroundColor),'rgba(0, 0, 0, 0)');
  assert.equal(await page.evaluate(()=>getComputedStyle(document.body).backgroundColor),'rgba(0, 0, 0, 0)');
  await page.screenshot({path:path.join(output,'markers-transparent-27px.png'),omitBackground:true});
  assert.deepEqual(errors,[]);fs.writeFileSync(path.join(output,'browser-result.json'),JSON.stringify(result,null,2)+'\n');
  console.log('PASS: 7 rows; player roles, SVG accessibility, real 27px size, stable rows/animations, narrow Cyrillic/UNKNOWN layout, custom colors and transparency.');
 } finally {if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(e=>{console.error(e);process.exitCode=1;});
