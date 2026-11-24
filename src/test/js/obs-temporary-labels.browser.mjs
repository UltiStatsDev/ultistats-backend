// Own static HTTP + synthetic canonical snapshot -> production projection/Feed/Renderer; not product E2E.
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFileSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
const evidence=process.env.OBS248_EVIDENCE, served={}, requests=[];
const files=['feed.mjs','protected-event-log-model.mjs','renderer.mjs','colors.mjs','obs.css'].map(name=>'src/main/resources/static/obs/'+name);
files.push('src/test/js/fixtures/obs-temporary-labels.mjs');
const server=createServer((request,response)=>{
 const path=new URL(request.url,'http://localhost').pathname;requests.push(path);
 if(path==='/')return response.end('<!doctype html><html lang="ru"><meta charset="utf-8"><link rel="stylesheet" href="/src/main/resources/static/obs/obs.css"><body style="background:#17202e"><main id="feed"></main></body></html>');
 const relative=path.slice(1);if(!files.includes(relative)){response.writeHead(404);return response.end();}
 const bytes=readFileSync(resolve(relative));served[relative]=createHash('sha256').update(bytes).digest('hex');
 response.setHeader('Content-Type',relative.endsWith('.css')?'text/css':'text/javascript');response.end(bytes);
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const origin=`http://127.0.0.1:${server.address().port}`;let browser;
try {
 const {chromium}=await import(pathToFileURL(process.env.OBS_PLAYWRIGHT).href);
 browser=await chromium.launch({executablePath:'/usr/bin/chromium',headless:true});
 const page=await browser.newPage({viewport:{width:1000,height:450}}),errors=[];
 page.on('pageerror',error=>errors.push(error.message));await page.goto(origin);
 const pair=await page.evaluate(async()=>{
  const {eventLogMatch}=await import('/src/main/resources/static/obs/protected-event-log-model.mjs');
  const {Feed}=await import('/src/main/resources/static/obs/feed.mjs');
  const {Renderer}=await import('/src/main/resources/static/obs/renderer.mjs');
  const {temporarySnapshot,instant,ids}=await import('/src/test/js/fixtures/obs-temporary-labels.mjs');
  const snapshot=temporarySnapshot(),before=JSON.stringify(snapshot);let now=0;
  const feed=new Feed(snapshot.config,()=>now,{protected:true}),renderer=new Renderer(document.querySelector('#feed'));
  const labels=()=>[...document.querySelectorAll('.feed-row:not(.leaving)')].map(row=>({kind:row.dataset.kind,left:row.querySelector('.left').textContent,right:row.querySelector('.right').textContent,accent:row.querySelector('.left').style.getPropertyValue('--accent')}));
  feed.reconcile({...snapshot,events:[]});feed.reconcile({...snapshot,logRevision:2});
  renderer.render(feed.rows(),eventLogMatch(snapshot),snapshot.config);const original=labels();
  now=3000;const cancelled={...snapshot,logRevision:3,serverNow:instant(now),events:[],retractions:[{event:snapshot.events[0],cancelledAt:instant(now)}]};
  feed.reconcile(cancelled);renderer.render(feed.rows(),eventLogMatch(cancelled),snapshot.config);
  const cases=()=>{
   const display=(value,event=snapshot.events[0])=>{renderer.dispose();renderer.render([{event}],eventLogMatch(value),snapshot.config);return labels()[0]};
   const known=structuredClone(snapshot);known.temporarySlots[0].resolvedParticipantId=ids.known;
   const wrongTeam=structuredClone(snapshot);wrongTeam.temporarySlots[0].resolvedParticipantId=ids.opposing;
   const missing=structuredClone(snapshot);missing.temporarySlots=[];
   return {known:display(known),wrongTeam:display(wrongTeam),legacy:display(snapshot,{...snapshot.events[0],fromParticipantId:ids.legacy}),missing:display(missing)};
  };
  window.syntheticCases=cases;
  return {original,pair:labels(),keys:feed.rows().map(row=>row.key),cancellation:document.querySelector('.cancel-label').textContent,payloadUnchanged:JSON.stringify(snapshot)===before,maxRows:snapshot.config.maxRows,ttlSeconds:snapshot.config.ttlSeconds};
 });
 assert.equal(pair.maxRows,1);assert.equal(pair.ttlSeconds,45);assert.equal(pair.payloadUnchanged,true);
 assert.deepEqual(pair.pair.map(row=>row.kind),['cancellation','original']);assert.notEqual(...pair.keys);
 for(const row of [...pair.original,...pair.pair]){assert.equal(row.left,'Запасной №1');assert.equal(row.right,'Запасной №2');assert.equal(row.accent,'#ffcc00');}
 assert.equal(pair.cancellation,'Отмена: Гол');
 await page.waitForFunction(()=>[...document.querySelectorAll('.feed-row')].every(row=>getComputedStyle(row).opacity==='1'));await page.screenshot({path:evidence+'/temporary-original-cancellation.png'});
 const cases=await page.evaluate(()=>window.syntheticCases());
 assert.equal(cases.known.left,'7 Иванов И.');assert.equal(cases.wrongTeam.left,'Запасной №1');
 assert.equal(cases.wrongTeam.accent,'#ffcc00');assert.equal(cases.legacy.left,'Неизвестный игрок');assert.equal(cases.missing.left,'Неизвестный игрок');assert.deepEqual(errors,[]);
 writeFileSync(evidence+'/browser-results.json',JSON.stringify({origin,pair,cases,served,requests,limits:'Owned transient static HTTP and synthetic canonical snapshot renderer seam only; no auth/PG/ProductQA/shared QA/candidate27078 access.'},null,2)+'\n');
 console.log('PASS synthetic Chromium: original+separate cancellation temporary labels, known and legacy/missing/wrong-team negatives, immutable payload/team accents');
}finally{await browser?.close();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
