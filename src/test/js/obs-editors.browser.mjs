// Called by the owned HTTPS combined fixture; no tracing or credential output.
import assert from 'node:assert/strict';
export function instrumentLegacyTransport() {
  globalThis.__legacyTransportCalls=[];
  for(const name of ['SharedWorker','EventSource'])globalThis[name]=function(){globalThis.__legacyTransportCalls.push(name);throw Error('Legacy transport constructed by new staff consumer');};
}
export async function verifyEditorTools(page,input,sql) {
  const {match,origin,staff}=input;
  for(const fold of ['log-settings','log-preview-settings','scoreboard-settings'])
   await page.locator(`#${fold} > summary`).click();
  await page.waitForFunction(id=>document.querySelector('#preview').getAttribute('src')===`/obs-editor/event-log.html?matchId=${id}`,match);
  assert.equal(await page.locator('#preview').getAttribute('src'),`/obs-editor/event-log.html?matchId=${match}`);
  assert.equal(await page.locator('#scoreboard-preview').getAttribute('src'),`/obs-editor/scoreboard.html?matchId=${match}`);
  const log=page.frameLocator('#preview'),score=page.frameLocator('#scoreboard-preview');
  await score.locator('.scoreboard-score').waitFor();
  const configSave=page.waitForResponse(r=>r.request().method()==='PUT'&&new URL(r.url()).pathname==='/api/v1/obs/config' && new URL(r.url()).searchParams.get('matchId')===match);
  await page.locator('#ttlSeconds').fill('2');await page.locator('#corner').selectOption('TOP_LEFT');await page.locator('#save').click();assert.equal((await configSave).status(),200);
  await page.waitForFunction(()=>document.querySelector('#preview').contentDocument.querySelector('#feed').dataset.corner==='TOP_LEFT');
  const count=sql('SELECT count(*) FROM events;');
  await page.locator('#test').click();await log.locator('.feed-row[data-event-id="preview-test"]').waitFor();
  assert.equal(sql('SELECT count(*) FROM events;'),count);
  // A local editor test must not enter any real protected data snapshot.
  const source=await page.context().request.get(`${origin}/api/v1/obs/previews/event-log/${match}`,{headers:staff});
  assert.equal(source.status(),200);assert.equal((await source.json()).events.length,0);
  await page.waitForFunction(()=>document.querySelector('#preview').contentDocument.querySelectorAll('.feed-row').length===0);
  await page.locator('#test').click();await log.locator('.feed-row[data-event-id="preview-test"]').waitFor();
  await page.locator('[data-scoreboard-name]').first().fill('Local draft');
  await score.locator('.scoreboard-name').filter({hasText:'Local draft'}).waitFor();
  await page.locator('#scoreboard-swap').click();
  await page.waitForFunction(()=>document.querySelector('#scoreboard-preview').contentDocument.querySelector('.scoreboard-team.right .scoreboard-name')?.textContent==='Local draft');
  await page.locator('#scoreboard-reset').click();
  await page.waitForFunction(()=>document.querySelector('#scoreboard-preview').contentDocument.querySelector('.scoreboard-team.left .scoreboard-name')?.textContent==='Left');
  assert.equal(await page.locator('[data-scoreboard-name]').first().inputValue(),'');
  // Pagehide ends timers/rows and ignores subsequent parent test/draft messages.
  await page.locator('#preview').evaluate(frame=>frame.contentWindow.dispatchEvent(new Event('pagehide')));
  await page.locator('#test').click();assert.equal(await log.locator('.feed-row').count(),0);
  await page.locator('#preview').evaluate(frame=>frame.contentWindow.location.reload());
  await log.locator('#status').waitFor({state:'attached'});
  for(const frame of page.frames())assert.equal(await frame.evaluate(()=>globalThis.__legacyTransportCalls.length),0);
}
export async function verifyEditorSeal(page) {
  assert.equal(await page.locator('#preview').getAttribute('src'),'about:blank');
  assert.equal(await page.locator('#scoreboard-preview').getAttribute('src'),'about:blank');
  assert.equal(await page.locator('[data-scoreboard-name]').first().inputValue(),'F2 draft');
  assert.equal(await page.locator('#maxRows').inputValue(),'4');
  assert.equal(await page.locator('#maxRows').isDisabled(),true);
  assert.match(await page.locator('#auth-status').textContent(),/Сессия изменилась/);
}
export async function verifyEditorRevocation(browser,input,sql) {
  const context=await browser.newContext();await context.addInitScript(instrumentLegacyTransport);
  await context.addCookies(Object.entries(input.cookies).map(([name,value])=>({name,value,url:input.origin,secure:true})));
  const pages=[];
  for(const kind of ['scoreboard','event-log']) {
    const page=await context.newPage();const response=page.waitForResponse(r=>r.url().includes(`/api/v1/obs/previews/${kind}/`)&&r.status()===200);
    await page.goto(`${input.origin}/obs-editor/${kind}.html?matchId=${input.match}`);await response;pages.push(page);
  }
  sql(`UPDATE auth_sessions SET revoked_at=now() WHERE id='${input.staff['X-UltiStats-Session-Id']}';`);
  for(const page of pages)await page.waitForFunction(()=>/недоступна|Сессия/.test(document.querySelector('#status').textContent));
  assert.equal(await pages[0].locator('#scoreboard').isHidden(),true);assert.equal(await pages[1].locator('.feed-row').count(),0);
  await context.close();
}
