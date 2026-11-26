// OBS_TLS_KEY and OBS_TLS_CERT designate a localhost certificate trusted by Chromium.
// Optionally set OBS_PLAYWRIGHT, OBS_CHROMIUM, OBS_STATIC_DIR and OBS_SCREENSHOTS.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createServer} from 'node:https';
import {join} from 'node:path';
import {pathToFileURL} from 'node:url';
import {contrast} from '../../main/resources/static/obs/colors.mjs';

const {chromium} = process.env.OBS_PLAYWRIGHT
  ? await import(pathToFileURL(process.env.OBS_PLAYWRIGHT).href)
  : await import('playwright');
const assets = process.env.OBS_STATIC_DIR
  ? pathToFileURL(join(process.env.OBS_STATIC_DIR, 'obs') + '/')
  : new URL('../../main/resources/static/obs/', import.meta.url);
const fixture = `<!doctype html><html lang="ru"><meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="stylesheet" href="/obs/obs.css"><main id="feed" aria-label="События матча"></main>`;
const routes = new Map([['/', ['text/html; charset=utf-8', fixture]],
  ...['obs.css', 'renderer.mjs', 'feed.mjs', 'colors.mjs'].map(file => [
    `/obs/${file}`, [file.endsWith('.mjs') ? 'text/javascript' : 'text/css', readFileSync(new URL(file, assets))],
  ]),
]);
const server = createServer({
  key: readFileSync(process.env.OBS_TLS_KEY),
  cert: readFileSync(process.env.OBS_TLS_CERT),
}, (req, res) => {
  const route = routes.get(req.url);
  if (route) res.writeHead(200, {'content-type': route[0]}).end(route[1]);
  else res.writeHead(404).end();
});
await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve));
let browser;
const widths = [1024, 640, 320];
const cases = [
  {name:'short', type:'GOAL', labels:['Ли', 'Ким'], mark:'Гол'},
  {name:'long', type:'INTERCEPTION', labels:['Лебедева-Воскресенская-Александрова', 'Александров-Рождественский-Лебедев'], mark:'Перехват'},
  {name:'reserve', type:'BLOCK', labels:['Запасной №12', 'Запасной №27'], mark:'Блок'},
];
const backgrounds = [
  {background:'#17202e', originalText:'#fefefe', cancelText:'#ffc0ca'},
  {background:'#f2eee9', originalText:'#234566', cancelText:'#791d32'},
  {background:'#777777', originalText:'#040404', cancelText:'#000000'},
];
const accents = ['#ffcc00', '#00ccff'];
const hex = rgb => '#' + rgb.match(/\d+/g).map(value => Number(value).toString(16).padStart(2, '0')).join('');
const close = (a, b, label) => assert.ok(Math.abs(a-b) <= 1, `${label}: ${a} vs ${b}`);
const failures = [];
try {
  browser = await chromium.launch({executablePath: process.env.OBS_CHROMIUM});
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const response = await page.goto(`https://localhost:${server.address().port}/`);
  assert.equal(response.status(), 200);
  assert.match((await response.securityDetails()).protocol, /^TLS/);
  assert.equal(await page.evaluate(() => isSecureContext), true);
  for (const width of widths) {
    await page.setViewportSize({width, height: 300});
    // The product stylesheet defaults to 20px. Stress layout at smaller and larger type as well.
    for (const fontSize of [16, null, 24]) {
      for (const {background, originalText, cancelText} of backgrounds) {
        await page.emulateMedia({reducedMotion: !fontSize && background === '#17202e' ? 'no-preference' : 'reduce'});
        for (const sample of cases) {
          const result = await page.evaluate(async ({sample, fontSize, background, originalText, accents}) => {
            const {Renderer} = await import('/obs/renderer.mjs');
            const {defaults} = await import('/obs/feed.mjs');
            const feed = document.querySelector('#feed');
            feed.replaceChildren();
            const renderer = new Renderer(feed);
            const event = {id:'same', type:sample.type, fromParticipantId:'left', toParticipantId:'right'};
            const teams = ['left', 'right'].map((id, index) => ({
              teamId:id, participants:[sample.name === 'reserve'
                ? {participantId:id, kind:'TEMPORARY', number:index ? 27 : 12}
                : {participantId:id, lastName:sample.labels[index]}],
            }));
            const config = {...defaults, backgroundColor: background,
              textColor: originalText,
              teamColors: {left:accents[0], right:accents[1]}};
            const original = {event, key:'original', kind:'original'};
            renderer.render([original], {teams}, config);
            const live = feed.querySelector('.feed-row');
            if (fontSize) live.style.fontSize = `${fontSize}px`;
            await Promise.all(live.getAnimations().map(animation => animation.finished));
            const box = element => element.getBoundingClientRect().toJSON();
            const alone = box(live);

            renderer.render([{event, key:'cancel', kind:'cancellation'}, original], {teams}, config);
            const cancelled = feed.querySelector('[data-kind=cancellation]');
            if (fontSize) cancelled.style.fontSize = `${fontSize}px`;
            await Promise.all(cancelled.getAnimations().map(animation => animation.finished));
            const data = element => {
              const range = new Range();
              range.selectNodeContents(element);
              return {
                text:element.textContent, outer:box(element), textBox:box(range),
                color:getComputedStyle(element).color,
                fits:element.scrollWidth <= element.clientWidth,
              };
            };
            const describe = row => ({
              outer:box(row), color:getComputedStyle(row).color,
              fontSize:parseFloat(getComputedStyle(row).fontSize),
              names:[...row.querySelectorAll('.player')].map(data),
              accents:['left', 'right'].map(side => getComputedStyle(row.querySelector(`.player.${side}`))
                .getPropertyValue(`border-${side}-color`)),
              icon:box(row.querySelector('svg')), mark:row.querySelector('svg').getAttribute('aria-label'),
            });
            const line = getComputedStyle(cancelled, '::after');
            return {alone, original:describe(live), cancel:describe(cancelled), label:data(cancelled.querySelector('.cancel-label')),
              line:{content:line.content, left:parseFloat(line.left), width:parseFloat(line.width),
                top:parseFloat(line.top), height:parseFloat(line.height), color:line.backgroundColor},
              scrollWidth:document.documentElement.scrollWidth};
          }, {sample, fontSize, background, originalText, accents});
          const {alone, original, cancel, label, line} = result;
          const id = `${width}/${fontSize ?? 'default'}/${background}/${sample.name}`;
          console.log(id, JSON.stringify({alone, original:original.outer, cancel:cancel.outer, label:label.outer,
            line, names:cancel.names.map(name => name.fits)}));
          if (process.env.OBS_SCREENSHOTS && !fontSize && background === '#17202e') {
            await page.screenshot({path:join(process.env.OBS_SCREENSHOTS, `${width}-${sample.name}.png`)});
          }
          try {
            for (const axis of ['width', 'height']) {
              close(alone[axis], original.outer[axis], `${id} original retains ${axis}`);
              close(original.outer[axis], cancel.outer[axis], `${id} paired ${axis}`);
            }
            for (const row of [original, cancel]) {
              assert.equal(row.fontSize, fontSize ?? 20);
              assert.ok(row.outer.left >= 0 && row.outer.right <= width && result.scrollWidth <= width, id);
              assert.deepEqual(row.names.map(name => name.text), sample.labels);
              assert.deepEqual(row.accents.map(hex), accents);
              assert.equal(row.mark, sample.mark);
              assert.ok(row.icon.left >= row.outer.left && row.icon.right <= row.outer.right, id);
              close(row.icon.width, 27, `${id} marker width`);
            }
            assert.deepEqual(cancel.names.map(name => name.fits), original.names.map(name => name.fits), id);
            assert.equal(hex(original.color), originalText);
            assert.equal(label.text, `Отмена: ${sample.mark}`);
            assert.ok(label.fits && label.textBox.left >= cancel.outer.left && label.textBox.right <= cancel.outer.right, id);
            assert.ok(label.textBox.top >= cancel.outer.top && label.textBox.bottom <= cancel.icon.top, id);
            assert.ok(label.textBox.bottom <= Math.min(...cancel.names.map(name => name.textBox.top)) + 1, id);
            assert.ok(contrast(hex(label.color), background) >= 4.5, id);
            assert.equal(label.color, cancel.color);
            assert.equal(hex(cancel.color), cancelText);
            assert.equal(line.content, '""');
            assert.equal(line.height, 1.5);
            assert.equal(line.color, cancel.color);
            const start = cancel.outer.left + line.left, end = start + line.width;
            assert.ok(start > cancel.outer.left && start <= cancel.names[0].textBox.left, id);
            assert.ok(end >= Math.min(cancel.names[1].textBox.right, cancel.names[1].outer.right - 21) && end < cancel.outer.right, id);
            const middle = cancel.outer.top + line.top + line.height/2;
            for (const elem of [...cancel.names.map(name => name.textBox), cancel.icon]) {
              assert.ok(elem.top < middle && elem.bottom > middle, id);
            }
          } catch (error) {
            failures.push(error.message);
          }
        }
      }
    }
  }
  await page.keyboard.press('Tab');
  assert.equal(await page.evaluate(() => document.activeElement.tagName), 'BODY');
  assert.match(await page.locator('#feed').ariaSnapshot(), /Отмена: Блок/);
  assert.deepEqual(errors, []);
  assert.deepEqual(failures, [], 'cancellation geometry and legibility');
  console.log('81 HTTPS Chromium cases passed (three widths, typography settings, backgrounds and label samples).');
} finally {
  await browser?.close();
  await new Promise(resolve => server.close(resolve));
}
