// Synthetic integration only. Run against the dedicated OBS135 backend origin.
import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
import {mkdirSync,writeFileSync} from 'node:fs';
const require=createRequire(import.meta.url),{chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const base=process.env.OBS135_BASE||'http://127.0.0.1:19835';
const output=process.env.OBS135_OUTPUT||'/tmp/obs135-runtime/browser';mkdirSync(output,{recursive:true});
async function req(path,method='GET',value){const r=await fetch(base+path,{method,headers:{'Content-Type':'application/json'},body:value===undefined?undefined:JSON.stringify(value)});
 const text=await r.text();assert.ok(r.ok,`${method} ${path}: ${r.status} ${text}`);return text?JSON.parse(text):null;}
const pause=ms=>new Promise(r=>setTimeout(r,ms));
async function until(read,want,ms=2000){const start=performance.now();let value;do{value=await read();if(want(value))return performance.now()-start;await pause(30);}while(performance.now()-start<ms);assert.fail(`condition timeout: ${JSON.stringify(value)}`);}
const names=['Очень длинная кириллическая команда Красноярского университета','Соперники'];
const teams=await Promise.all(names.map(name=>req('/api/v1/teams','POST',{name})));
const matches=[];for(let i=0;i<2;i++)matches.push(await req('/api/v1/matches','POST',{teamIds:teams.map(t=>t.id),playersPerTeam:2}));
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
const context=await browser.newContext({viewport:{width:1000,height:160}}),errors=[];
context.on('page',p=>p.on('pageerror',e=>errors.push(e.message)));
const source=async id=>{const p=await context.newPage();await p.goto(`${base}/obs-scoreboard.html?matchId=${id}`);await p.locator('.scoreboard-score').waitFor();return p;};
try {
 const live=await source(matches[0].id),live2=await source(matches[0].id),other=await source(matches[1].id);
 const admin=await context.newPage();await admin.setViewportSize({width:1440,height:1100});await admin.goto(base+'/obs-admin.html');
 await admin.locator('#match-id').fill(matches[0].id);await admin.locator('#open-match').click();
 await admin.locator('#scoreboard-settings').waitFor({timeout:3000});
 const preview=admin.frameLocator('#scoreboard-preview');await preview.locator('.scoreboard-score').waitFor();
 const short=admin.locator('[data-scoreboard-name]').first();await short.fill('Черновик');await pause(1200);
 assert.ok(await short.evaluate(node=>document.activeElement===node),'background snapshot must not replace an input while typing');
 await short.fill('ЭФИР');
 await until(()=>preview.locator('.scoreboard-name').first().textContent(),v=>v==='ЭФИР');
 assert.equal(await live.locator('.scoreboard-name').first().textContent(),names[0]);
 assert.equal(await live2.locator('.scoreboard-name').first().textContent(),names[0]);
 await admin.locator('[data-scoreboard-color]').first().fill('#ffffff');
 await admin.locator('#scoreboard-swap').click();
 await until(()=>preview.locator('.scoreboard-team').first().getAttribute('data-team-id'),v=>v===teams[1].id);
 assert.equal(await live.locator('.scoreboard-team').first().getAttribute('data-team-id'),teams[0].id);
 await admin.locator('#scoreboard-save').click();
 const saveSla=await until(()=>live.locator('.scoreboard-name').last().textContent(),v=>v==='ЭФИР');
 await until(()=>live2.locator('.scoreboard-name').last().textContent(),v=>v==='ЭФИР');
 assert.equal(await other.locator('.scoreboard-name').first().textContent(),names[0]);
 await admin.locator('#scoreboard-reset').click();
 await until(()=>preview.locator('.scoreboard-name').first().textContent(),v=>v===names[0]);
 assert.equal(await live.locator('.scoreboard-name').last().textContent(),'ЭФИР');
 await admin.locator('#scoreboard-save').click();await until(()=>live.locator('.scoreboard-name').first().textContent(),v=>v===names[0]);
 const actualTeams=await Promise.all(teams.map(t=>req('/api/v1/teams/'+t.id)));assert.deepEqual(actualTeams.map(t=>t.name),names);
 assert.equal((await req('/api/v1/matches/'+matches[0].id)).teams[0].teamId,teams[0].id);
 assert.equal((await req('/api/v2/matches/'+matches[0].id+'/events')).events.length,0);
 // A slow old match response cannot replace a newer selection.
 await admin.route(`**/api/v1/obs/scoreboard/${matches[0].id}`,async route=>{await pause(250);await route.continue().catch(()=>{});});
 await admin.locator('#match-id').fill(matches[0].id);await admin.locator('#open-match').click();
 await admin.locator('#match-id').fill(matches[1].id);await admin.locator('#open-match').click();await pause(500);
 assert.equal(new URL(await admin.locator('#scoreboard-url').inputValue()).searchParams.get('matchId'),matches[1].id);
 assert.equal(new URL(live.url()).searchParams.get('matchId'),matches[0].id);
 await live.screenshot({path:output+'/source.png',omitBackground:true});await admin.screenshot({path:output+'/admin.png',fullPage:true});
 assert.deepEqual(errors,[]);writeFileSync(output+'/result.json',JSON.stringify({base,teams:teams.map(t=>t.id),matches:matches.map(m=>m.id),saveSla,errors},null,2));
 console.log(JSON.stringify({result:'PASS',saveSla,fixtures:output+'/result.json'}));
}finally{await browser.close();}
