// Run with OBS_PLAYWRIGHT pointing to Playwright's entry module and, optionally,
// OBS_CHROMIUM pointing to a Chromium executable. The fixture makes no live requests.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';

const {chromium} = process.env.OBS_PLAYWRIGHT
  ? await import(pathToFileURL(process.env.OBS_PLAYWRIGHT).href)
  : await import('playwright');
const assets = new URL('../../main/resources/static/obs/', import.meta.url);
const fixture = `<!doctype html><meta charset="utf-8">
  <link rel="stylesheet" href="/infographics.css">
  <link rel="stylesheet" href="/infographics-postmatch.css">
  <style>#frame { position: relative; width: 1920px; height: 1080px; transform-origin: top left; }</style>
  <div id="frame"><main id="stage"><article id="infographics" data-graphic-kind="postmatch"></article></main></div>`;
const routes = new Map([
  ['/', ['text/html', fixture]],
  ...['infographics.css', 'infographics-postmatch.css', 'infographics-postmatch.mjs'].map(file => [
    `/${file}`, [file.endsWith('.mjs') ? 'text/javascript' : 'text/css', readFileSync(new URL(file, assets))],
  ]),
]);
const values = [0, 9, 10, 99, 100, null];
const browser = await chromium.launch({executablePath: process.env.OBS_CHROMIUM});

function close(actual, expected, description) {
  assert.ok(Math.abs(actual - expected) <= 0.05, `${description}: ${actual} ≠ ${expected}`);
}

try {
  const page = await browser.newPage({viewport: {width: 1920, height: 1080}, reducedMotion: 'reduce'});
  await page.route('**/*', route => {
    const response = routes.get(new URL(route.request().url()).pathname);
    return response ? route.fulfill({contentType: response[0], body: response[1]}) : route.abort();
  });
  await page.goto('http://postmatch.test/');

  for (const scale of [1, 0.4]) {
    let columns;
    for (const offset of values.keys()) {
      const pairs = values.map((_, index) => [values[(index + offset) % values.length],
        values[(index + offset + 1) % values.length]]);
      const {frame, rows} = await page.evaluate(async ({scale, pairs}) => {
        const {renderPostmatch, validatePostmatch} = await import('/infographics-postmatch.mjs');
        const node = (tag, text, className = '') => {
          const element = document.createElement(tag);
          element.textContent = text;
          element.className = className;
          return element;
        };
        const teams = [{teamId: 'left', name: 'Север'}, {teamId: 'right', name: 'Юг'}];
        const keys = ['accuratePasses', 'inaccuratePasses', 'blocks', 'interceptions', 'callahans', 'breaks'];
        const stats = validatePostmatch({statsAsOfEventAt: null, teams: teams.map(({teamId}, side) => ({
          teamId, score: 0, accuracy: 0, possession: 0.5,
          metrics: Object.fromEntries(keys.map((key, i) => [key, pairs[i][side]])),
        }))}, teams);
        const frame = document.querySelector('#frame');
        frame.style.transform = `scale(${scale})`;
        document.querySelector('#infographics').replaceChildren(renderPostmatch(node, teams, stats));
        const box = element => element.getBoundingClientRect().toJSON();
        const textBox = element => {
          const range = new Range();
          range.selectNodeContents(element);
          return box(range);
        };
        return {frame: box(frame), rows: [...document.querySelectorAll('.postmatch-row')].map(row => ({
          tracks: [...row.querySelectorAll('.postmatch-track')].map(box),
          fills: [...row.querySelectorAll('.postmatch-fill')].map(box),
          counts: [...row.querySelectorAll('.postmatch-count')].map(element => ({
            box: box(element), text: element.textContent, textBox: textBox(element),
          })),
          label: textBox(row.querySelector('h3')),
          spoken: row.getAttribute('aria-label'),
        }))};
      }, {scale, pairs});

      const center = box => box.left + box.width / 2;
      close(frame.width, 1920 * scale, 'frame width');
      close(frame.height, 1080 * scale, 'frame height');
      assert.equal(rows.length, 6);
      for (const [index, {tracks: [left, right], fills, counts, label, spoken}] of rows.entries()) {
        const description = `scale ${scale}, row ${index + 1}, values ${pairs[index].join('/')}`;
        close(left.width, right.width, `${description}: equal tracks`);
        close(center(label), center(frame), `${description}: title center`);
        close(counts[0].box.width, counts[1].box.width, `${description}: equal value columns`);
        close((center(counts[0].box) + center(counts[1].box)) / 2, center(frame), `${description}: mirrored values`);
        close(fills[0].right, left.right, `${description}: left fill starts at inner edge`);
        close(fills[1].left, right.left, `${description}: right fill starts at inner edge`);
        const [l, r] = pairs[index];
        const expectedScale = l === null || r === null ? null : Math.max(1, l, r);
        const positions = [left.right, right.left, ...counts.map(count => center(count.box))];
        columns ??= positions;
        positions.forEach((position, side) => close(position, columns[side], `${description}: stable column ${side}`));
        counts.forEach((count, side) => {
          const value = pairs[index][side];
          assert.equal(count.text, value === null ? '—' : String(value));
          if (value === null) assert.ok(spoken.includes(`${side === 0 ? 'Север' : 'Юг'} нет данных`));
          close(center(count.textBox), center(count.box), `${description}: value ${side} is centered`);
          assert.ok(count.textBox.width <= count.box.width + 0.05, `${description}: value ${side} fits`);
          const ratio = expectedScale === null ? 0 : value / expectedScale;
          close(fills[side].width, ratio * [left, right][side].width, `${description}: fill ${side} width`);
        });
      }
    }
  }
} finally {
  await browser.close();
}

console.log('Six postmatch rows keep mirrored track and value geometry at 1920×1080 and 40% scale.');
