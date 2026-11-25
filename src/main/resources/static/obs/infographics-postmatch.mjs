const metrics = [
  ['accuratePasses', 'Точные пасы'],
  ['inaccuratePasses', 'Неточные пасы'],
  ['blocks', 'Блоки'],
  ['interceptions', 'Перехваты'],
  ['callahans', 'Кэллаханы'],
  ['breaks', 'Брейки'],
];

const count = value => Number.isSafeInteger(value) && value >= 0 ? value : null;
const fraction = value => Number.isFinite(value) && value >= 0 && value <= 1 ? value : null;
const display = value => value === null ? '—' : String(value);
const percentage = value => value === null ? '—' : (value * 100).toFixed(1) + ' %';

export function validatePostmatch(value, teams) {
  if (!Array.isArray(value?.teams) || value.teams.length !== teams.length ||
      (value.statsAsOfEventAt !== null && !Number.isFinite(Date.parse(value.statsAsOfEventAt))) ||
      !teams.every((team, index) => team.teamId === value.teams[index]?.teamId)) {
    throw Error('Некорректные данные инфографики');
  }
  return {
    statsAsOfEventAt: value.statsAsOfEventAt,
    teams: value.teams.map(team => ({
      teamId: team.teamId,
      score: count(team.score),
      accuracy: fraction(team.accuracy),
      possession: fraction(team.possession),
      possessionTimeMs: count(team.possessionTimeMs),
      metrics: Object.fromEntries(metrics.map(([key]) => [key, count(team.metrics?.[key])])),
    })),
  };
}

export function renderPostmatch(node, teams, postmatch) {
  const panel = node('div', '', 'postmatch-panel');
  const summary = node('div', '', 'postmatch-summary');
  const comparison = node('div', '', 'postmatch-comparison');

  function ring(label, value) {
    const group = node('figure', '', 'postmatch-ring-item');
    const circle = node('div', '', 'postmatch-ring');
    const text = percentage(value);
    if (value !== null) circle.style.background = `conic-gradient(var(--postmatch-color) ${value * 100}%, #344055 0)`;
    circle.setAttribute('aria-hidden', 'true');
    circle.append(node('b', text, 'postmatch-ring-value'));
    const caption = node('figcaption', label);
    caption.append(node('span', value === null ? ' · Нет данных' : ` · ${text}`, 'postmatch-screen-reader'));
    group.append(circle, caption);
    return group;
  }

  function bar(value, scale) {
    const group = node('div', '', 'postmatch-bar');
    const track = node('div', '', 'postmatch-track');
    const fill = node('span', '', 'postmatch-fill');
    fill.style.width = `${value === null || scale === null ? 0 : value / scale * 100}%`;
    track.append(fill);
    group.append(node('b', display(value), 'postmatch-count'), track);
    return group;
  }

  summary.append(node('strong', postmatch.teams.map(team => display(team.score)).join(' : '), 'postmatch-score'));
  for (const [index, team] of postmatch.teams.entries()) {
    const side = node('section', '', 'postmatch-side');
    const rings = node('div', '', 'postmatch-rings');
    rings.append(ring('Точность передач', team.accuracy), ring('Время владения', team.possession));
    side.append(node('h3', teams[index].name), rings);
    summary.append(side);
  }
  const unavailable = postmatch.teams.every(team => team.possession === null);
  summary.append(node('p', unavailable ? 'Нет данных о владении.' : '', 'postmatch-no-possession'));

  for (const [key, name] of metrics) {
    const [left, right] = postmatch.teams.map(team => team.metrics[key]);
    const scale = left === null || right === null ? null : Math.max(1, left, right);
    const spoken = value => value === null ? 'нет данных' : value;
    const row = node('div', '', 'postmatch-row');
    row.setAttribute('role', 'img');
    row.setAttribute('aria-label', `${name} · ${teams[0].name} ${spoken(left)}, ${teams[1].name} ${spoken(right)}`);
    row.append(bar(left, scale), node('h3', name), bar(right, scale));
    comparison.append(row);
  }
  panel.append(node('h2', 'Матч завершён'), summary, comparison);
  return panel;
}
