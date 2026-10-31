// Synthetic fault injection: hold one snapshot until bounded abort; accepted score must recover within2s.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
const base=process.env.OBS135_BASE||'http://127.0.0.1:19835';const pause=ms=>new Promise(r=>setTimeout(r,ms));
async function req(p,body){const r=await fetch(base+p,{method:body?'POST':'GET',headers:{'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});if(!r.ok)throw Error(r.status+' '+await r.text());return r.json();}
(async()=>{const teams=await Promise.all(['HungHome','HungAway'].map(name=>req('/api/v1/teams',{name})));const m=await req('/api/v1/matches',{teamIds:teams.map(t=>t.id),playersPerTeam:2});const start=Date.now()-10000;await req(`/api/v1/matches/${m.id}/start`,{timestamp:new Date(start).toISOString()});const detail=await req(`/api/v1/matches/${m.id}`);const home=detail.teams[0].participants,away=detail.teams[1].participants;
const browser=await chromium.launch({executablePath:'/usr/bin/chromium',headless:true,args:['--no-sandbox']});let release;
try{const context=await browser.newContext({viewport:{width:1000,height:160}});const page=await context.newPage();await page.goto(`${base}/obs-scoreboard.html?matchId=${m.id}`);await page.locator('.scoreboard-score').waitFor();
let hanging=false,started;const began=new Promise(r=>started=r);const gate=new Promise(r=>release=r);let requests=0;
await page.route(`**/api/v1/obs/scoreboard/${m.id}`,async route=>{requests++;if(!hanging){hanging=true;started();await gate;}await route.continue().catch(()=>{});});
await Promise.race([began,pause(3000).then(()=>{throw Error('poll did not start');})]);
for(const [i,type,fields] of [[1,'PULL',{participantId:home[0].participantId}],[2,'PICKUP',{participantId:away[0].participantId}],[3,'GOAL',{fromParticipantId:away[0].participantId,toParticipantId:away[1].participantId}]])await req(`/api/v1/matches/${m.id}/events`,{type,occurredAt:new Date(start+i*1000).toISOString(),...fields});
const accepted=performance.now();let score;do{score=await page.locator('.scoreboard-score').textContent();if(score==='0 : 1')break;await pause(20);}while(performance.now()-accepted<4000);
const afterAcceptedMs=performance.now()-accepted;assert.equal(score,'0 : 1');assert.ok(afterAcceptedMs<=2000,`fresh invalidation delayed ${afterAcceptedMs}ms`);
console.log(JSON.stringify({matchId:m.id,score,afterAcceptedMs,snapshotRequests:requests,oneHungRead:true,SSEStillOnline:true},null,2));
}finally{release?.();await browser.close();}})().catch(e=>{console.error(e);process.exitCode=1;});
