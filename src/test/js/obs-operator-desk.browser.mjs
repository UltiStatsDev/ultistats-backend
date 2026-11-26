import assert from 'node:assert/strict';

export async function exerciseOperatorDesk({
  origin, staff, viewer, fixture, other, teams, otherPair, exact, commands, settings, control, readExact, readAuto,
  tally, confirm, score, finish, config, profile, readEventLogKey, screenshot,
}) {
  const page = await staff.newPage();
  const broadcast = await viewer.newPage();
  const errors = [], writes = [];
  page.on('pageerror', error => errors.push(error.name));
  page.on('request', request => {
    const path = new URL(request.url()).pathname;
    if (path.startsWith('/api/v1/obs/') && request.method() !== 'GET') writes.push(request);
  });
  const at = selector => page.locator(selector);
  const show = kind => at(`[data-show=${kind}]`);
  const visibleAir = kind => page.waitForFunction(kind => document.querySelector('#air-kind').dataset.kind === kind, kind);
  const match = id => page.waitForFunction(id => document.querySelector('#editor-context').dataset.matchId === id, id);
  const preview = value => at('#links-kind').selectOption(value);
  const exactMatch = async id => {
    await at('#links-mode').selectOption('exact');
    await at('#links-match').fill(id);
    await at('#links-open').click();
    await match(id);
  };
  async function action(button, method, url, expected = 200) {
    const response = page.waitForResponse(response => response.request().method() === method && new URL(response.url()).pathname === url);
    await button.click();
    const reply = await response;
    assert.equal(reply.status(), expected);
    return reply;
  }
  const operate = async kind => {
    await action(show(kind), 'POST', commands);
    const receipt = (await control()).activePresentation;
    assert.equal(receipt?.kind,kind);
    return receipt;
  };
  const hide = generation => action(at('#air-hide'),'DELETE',`${commands}/${generation}`,204);
  const frame = page.frameLocator('#links-preview');
  const airGraphic = broadcast.locator('#infographics');

  await Promise.all([page.goto(origin+'/obs-admin.html'), broadcast.goto(exact.url)]);
  assert.ok(await page.evaluate(() => isSecureContext));
  await match(fixture.id);
  await visibleAir('ROSTER');
  assert.equal(await at('#air-time').textContent(),'Автоматическая фаза');
  assert.ok(await show('POINT').isDisabled());
  assert.ok(await show('LEADERS').isDisabled());
  assert.ok(await show('STATS').isDisabled());
  await exactMatch(fixture.id);
  await visibleAir('ROSTER');
  await action(at('#air-hide'),'POST',commands+'/hide-phase',204);
  await visibleAir('');
  assert.equal((await readExact()).suppressedPhase,'ROSTER');
  assert.equal((await readAuto()).suppressedPhase,null);

  const roster = await operate('ROSTER');
  await visibleAir('ROSTER');
  assert.match(await at('#air-time').textContent(),/Ручной показ · \d+ с/);
  await airGraphic.waitFor();
  assert.equal((await readExact()).playerPresentation.generation,roster.generation);
  assert.equal((await readAuto()).playerPresentation,null);
  const beforeSwitch = writes.length;
  await exactMatch(other.id);
  assert.match(await at('#editor-context').textContent(),/Орион|Маяк/);
  await at('#links-mode').selectOption('auto');
  await match(fixture.id);
  assert.equal(writes.length,beforeSwitch);
  assert.equal((await control()).activePresentation.generation,roster.generation);
  await exactMatch(fixture.id);
  await visibleAir('ROSTER');
  await hide(roster.generation);

  await confirm();
  const point = await operate('POINT');
  assert.equal(point.kind,'POINT');
  assert.equal((await readExact()).playerPresentation.generation,point.generation);
  await visibleAir('POINT');
  await hide(point.generation);
  await score();
  const leader = await operate('LEADERS');
  await visibleAir('LEADERS');
  assert.equal((await readExact()).leaderPresentation.generation,leader.generation);
  await hide(leader.generation);

  await at('#color-settings > summary').click();
  const log = at(`[data-log-color="${teams[0].id}"]`);
  const board = at(`[data-scoreboard-color="${teams[0].id}"]`);
  await log.fill('#aa3412');
  await board.fill('#246bca');
  await action(at('#save-log-colors'),'PUT','/api/v1/obs/config');
  await action(at('#scoreboard-colors button[type=submit]'),'PUT',`/api/v1/obs/scoreboard/${fixture.id}/profile`);
  assert.deepEqual((await config()).teamColors,{[teams[0].id]:'#aa3412',[otherPair[0].id]:'#123456'});
  assert.equal((await profile()).teamColors[teams[0].id],'#246bca');
  await at('#color-settings > summary').click();

  const logWrites = writes.length;
  const localLogPath=`**/api/v1/obs/previews/event-log/${fixture.id}`;
  const previewRequest=Promise.withResolvers(), continuePreview=Promise.withResolvers();
  await page.route(localLogPath,async route=>{
    previewRequest.resolve();
    await continuePreview.promise;
    await route.continue();
  },{times:1});
  await at('#log-settings > summary').click();
  await at('#log-preview-settings > summary').click();
  const localLog = page.frameLocator('#preview');
  await previewRequest.promise;
  assert.ok(await at('#test').isDisabled());
  continuePreview.resolve();
  await at('#test').click();
  await localLog.locator('[data-event-id=preview-test]').waitFor();
  assert.equal(writes.length,logWrites);
  await at('#log-settings > summary').click();

  await finish();
  const automated = (await control()).activePresentation;
  assert.equal(automated.origin,'AUTO');
  assert.equal(Date.parse(automated.expiresAt) - Date.parse(automated.startedAt),10000);
  await visibleAir('STATS');
  const intervals = {rosterPageSeconds:6, pointPageSeconds:7, leaderPageSeconds:8, statsSeconds:3};
  for (const [key,value] of Object.entries(intervals)) await at(`[name=${key}]`).fill(String(value));
  await action(at('#infographics-settings button'),'PUT',settings);
  assert.equal((await control()).activePresentation.expiresAt,automated.expiresAt);
  await page.reload();
  await visibleAir('STATS');
  await exactMatch(fixture.id);
  await preview('infographics');
  await frame.locator('.postmatch-panel').waitFor();
  await visibleAir('');
  await frame.locator('.postmatch-panel').waitFor({state:'hidden'});
  await airGraphic.waitFor({state:'hidden'});
  assert.equal((await control()).activePresentation,null);

  const readOnlyTally = tally(), readOnlyWrites = writes.length;
  await preview('stats');
  await frame.locator('.postmatch-panel').waitFor();
  await page.screenshot({path:screenshot});
  const frameSizes = [];
  for (const width of [1440, 600]) {
    await page.setViewportSize({width,height:1000});
    await page.waitForFunction(() => {
      const outer = document.querySelector('#links-preview').getBoundingClientRect();
      const stage = document.querySelector('#preview-stage').getBoundingClientRect();
      return Math.abs(outer.width-stage.width)<1 && Math.abs(outer.height-stage.height)<1;
    });
    const size = await at('#links-preview').evaluate(el=>[el.contentWindow.innerWidth,el.contentWindow.innerHeight,el.getBoundingClientRect().width]);
    assert.deepEqual(size.slice(0,2),[1920,1080]);
    frameSizes.push(size);
  }
  assert.notEqual(frameSizes[0][2],frameSizes[1][2]);
  const refreshed = page.waitForResponse(response=>response.request().method()==='GET' &&
    new URL(response.url()).pathname===`/api/v1/obs/previews/infographics/${fixture.id}`);
  await at('#preview-refresh').click();
  assert.equal((await refreshed).status(),200);
  await frame.locator('.postmatch-panel').waitFor();
  assert.equal(tally(),readOnlyTally);
  assert.equal(writes.length,readOnlyWrites);

  for (const kind of ['scoreboard','event-log']) {
    const loaded = page.waitForResponse(r => new URL(r.url()).pathname===`/api/v1/obs/previews/${kind}/${fixture.id}`);
    await preview(kind);
    assert.equal((await loaded).status(),200);
    assert.equal(await at('#links-preview').getAttribute('src'),`/obs-preview/${kind}.html?matchId=${fixture.id}`);
  }
  await preview('stats');
  const manual = await operate('STATS');
  assert.equal(Date.parse(manual.expiresAt) - Date.parse(manual.startedAt),3000);
  assert.equal((await readExact()).statsPresentation.generation,manual.generation);
  assert.equal((await readAuto()).statsPresentation,null);
  await visibleAir('STATS');
  await hide(manual.generation);
  await visibleAir('');
  await frame.locator('.postmatch-panel').waitFor();
  await at('#active-links > summary').click();
  assert.equal(await at('#link-infographics-url').inputValue(),exact.url);
  const linkPath='/api/v1/obs/source-keys';
  const changeLink = async (actionId, method, path, status) => {
    await at(actionId).click();
    return action(at('#links-confirm'),method,path,status);
  };
  await changeLink('#link-event-log-issue','POST',linkPath,201);
  const first = await readEventLogKey();
  await changeLink('#link-event-log-issue','POST',linkPath,201);
  const rotated = await readEventLogKey();
  assert.notEqual(first.key.keyId,rotated.key.keyId);
  await changeLink('#link-event-log-revoke','DELETE',`${linkPath}/${rotated.key.keyId}`,204);
  assert.equal((await readEventLogKey()).status,'NO_ACTIVE_LINK');
  for (const request of writes) {
    assert.ok(await request.headerValue('x-ultistats-session-id'));
    assert.ok(await request.headerValue('x-csrf-token'));
    assert.equal(await request.headerValue('authorization'),null);
  }
  await staff.clearCookies();
  await page.waitForFunction(() => document.querySelector('#links-preview').getAttribute('src')==='about:blank' &&
    [...document.querySelectorAll('[data-show], #air-hide, #preview-refresh, #save, #scoreboard-fields')].every(el=>el.disabled));
  assert.deepEqual(errors,[]);
  return {frameSizes, colorPairPreserved:true, exactAutoIsolation:true, rosterPhaseHide:true, rosterPointLeadersAndStats:true,
    finiteHide:true, keyLifecycle:true, deadlinePreservedAcrossSaveAndReload:true, manualStatsMS:3000, readOnlyStatsAfterExpiry:true, sessionLoss:true};
}
