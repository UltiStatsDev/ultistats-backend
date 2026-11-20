// Actual owned PostgreSQL/Caddy HTTPS candidate. Credentials remain in memory; no trace/HAR.
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFileSync,writeFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';
const input=JSON.parse(readFileSync(0,'utf8'));
const {origin,exactUrl,autoUrl,pg,other:match,match:previous,team_a,team_b,evidence,spki,staff}=input;
const {chromium}=await import(pathToFileURL(process.env.OBS_PLAYWRIGHT).href);
const sql=text=>execFileSync('docker',['exec','-i',pg,'psql','-XqAt','-v','ON_ERROR_STOP=1','-U','obs_fixture','-d','obs_fixture'],{input:text,encoding:'utf8'});
const browser=await chromium.launch({executablePath:'/usr/bin/chromium',headless:true,args:[`--ignore-certificate-errors-spki-list=${spki}`]});
const results=[];
try {
 const context=await browser.newContext({viewport:{width:1000,height:300}}),page=await context.newPage();
 const actorA='00000000-0000-0000-0000-000000002371',actorB='00000000-0000-0000-0000-000000002372';
 sql(`INSERT INTO match_participants(match_id,participant_id,team_id,kind,first_name,last_name,number) VALUES
 ('${match}','${actorA}','${team_a}','PLAYER','Иван','Исторический',7),
 ('${match}','${actorB}','${team_b}','PLAYER','Петр','Защитник',8);
 UPDATE matches SET started_at=now(),ended_at=NULL WHERE id='${match}';`);
 const settings=(patch={})=>sql(`UPDATE obs_config SET payload='${JSON.stringify({categories:['GOAL','BLOCK','INTERCEPTION','CALLAHAN'],maxRows:1,ttlSeconds:6,corner:'TOP_RIGHT',backgroundColor:'#17202e',textColor:'#ffffff',teamColors:{[team_a]:'#ff0000',[team_b]:'#00ff00'},...patch})}';`);
 const add=(n,type='GOAL')=>{
  const id=`00000000-0000-0000-0000-${String(23700+n).padStart(12,'0')}`;
  sql(`INSERT INTO events(id,match_id,sequence_number,event_type,occurred_at,from_participant_id,to_participant_id)
  VALUES ('${id}','${match}',${n},'${type}',now(),'${actorA}','${actorB}');
  UPDATE matches SET event_log_revision=event_log_revision+1 WHERE id='${match}';`);return id;
 };
 const cancel=async id=>{
  const response=await context.request.delete(`${origin}/api/v1/matches/${match}/events/${id}`,{headers:staff});
  assert.equal(response.status(),204,`actual server DELETE status ${response.status()}`);
  assert.ok(sql(`SELECT deleted_at IS NOT NULL FROM events WHERE id='${id}';`).includes('t'));
 };
 const rows=()=>page.locator('.feed-row:not(.leaving)');
 const waitRows=n=>page.waitForFunction(n=>document.querySelectorAll('.feed-row:not(.leaving)').length===n,n);
 const settled=()=>page.waitForResponse(r=>r.url().includes('/api/v1/obs/sources/event-log/')&&r.status()===200);
 settings();await page.goto(exactUrl);await settled();
 const first=add(1);await waitRows(1);
 const original=await rows().first().elementHandle();
 await page.waitForTimeout(2000);await cancel(first);await waitRows(2);
 const wire=await (await settled()).json();
 const confirmed=wire.retractions.find(r=>r.event.id===first);
 assert.ok(confirmed && Date.parse(confirmed.cancelledAt)<=Date.parse(wire.serverNow));
 assert.ok(!wire.events.some(e=>e.id===first));
 writeFileSync(`${evidence}/actual-retraction-wire.json`,JSON.stringify({matchId:wire.matchId,serverNow:wire.serverNow,retraction:confirmed},null,2)+'\n');
 assert.deepEqual(await rows().evaluateAll(nodes=>nodes.map(n=>n.dataset.kind)),['cancellation','original']);
 assert.equal(await original.evaluate(n=>n.isConnected),true);
 assert.equal(await rows().first().locator('.cancel-label').textContent(),'Отмена: Гол');
 assert.equal(await rows().first().locator('.left').textContent(),'7 Исторический И.');
 assert.equal(await rows().first().locator('.right').textContent(),'8 Защитник П.');
 assert.equal(await rows().first().locator('.left').evaluate(n=>getComputedStyle(n).textDecorationLine),'line-through');
 assert.equal(await rows().last().locator('.left').evaluate(n=>getComputedStyle(n).textDecorationLine),'none');
 await page.screenshot({path:`${evidence}/separate-cancellation.png`,omitBackground:true});
 results.push('real staff DELETE persisted cancellation; maxRows1 retains original DOM plus separately labeled struck cancellation, canonical actors');
 // Repeated real snapshots do not add a third row or restart the original.
 await page.waitForTimeout(1200);assert.equal(await rows().count(),2);
 await waitRows(1);assert.equal(await rows().first().getAttribute('data-kind'),'cancellation');
 assert.equal(await original.evaluate(n=>n.classList.contains('leaving')||!n.isConnected),true);
 results.push('original expires first at its unchanged shown-at deadline; cancellation survives to its own server deadline');
 await page.reload();await settled();await waitRows(1);
 assert.equal(await rows().first().getAttribute('data-kind'),'cancellation');
 await waitRows(0);await page.reload();await settled();assert.equal(await rows().count(),0);
 results.push('fresh reload shows only unexpired cancellation; expired cancellation is not replayed');
 const second=add(2);await waitRows(1);await cancel(second);await waitRows(2);
 let offline=true;await page.route('**/api/v1/obs/sources/event-log/*',r=>offline?r.abort():r.continue());
 await page.waitForTimeout(1500);assert.equal(await rows().count(),2);
 offline=false;await settled();await page.unroute('**/api/v1/obs/sources/event-log/*');assert.equal(await rows().count(),2);
 results.push('transient network loss/reconnect retains confirmed pair without duplication');
 settings({categories:['BLOCK']});await waitRows(0);
 settings();await settled();assert.equal(await rows().count(),0);
 results.push('category filters prune both cards and repeat does not resurrect them');
 await page.goto(autoUrl);await settled();await waitRows(1);
 // AUTO fresh open may show the still-unexpired second cancellation.
 sql(`UPDATE matches SET started_at=now() WHERE id='${previous}';`);await waitRows(0);
 sql('UPDATE matches SET started_at=NULL,ended_at=NULL;');await page.waitForTimeout(1200);
 assert.equal(await page.locator('#feed').getAttribute('data-corner'),null);
 sql(`UPDATE matches SET started_at=now() WHERE id='${match}';`);await settled();
 await page.waitForTimeout(6500);assert.equal(await rows().count(),0);
 const third=add(3);await waitRows(1);await cancel(third);await waitRows(2);
 sql("UPDATE obs_source_keys SET revoked_at=now() WHERE scope='AUTO_EVENT_LOG';");await waitRows(0);
 results.push('AUTO A→B→null→A clears generation/config; real revoke clears cancellation and original');
 await page.goto(exactUrl);await settled();await waitRows(1);
 await page.evaluate(()=>dispatchEvent(new Event('pagehide')));assert.equal(await rows().count(),0);
 results.push('pagehide clears an unexpired cancellation');
 await context.close();
 writeFileSync(`${evidence}/retraction-browser-results.json`,JSON.stringify({results,limits:'Real loopback PostgreSQL, Caddy HTTPS and Chromium. Native OBS/CEF/ProductQA deferred. Fake-clock and late-photo proofs are separately identified in C regression and Node tests.'},null,2)+'\n');
 console.log(`PASS ${results.length} actual retraction browser scenarios`);
} finally {await browser.close();}
