import {readStaffJson} from './staff-reader.mjs';
const path = '/api/v1/obs/infographics/settings';
export function mountInfographicsSettings(session, form = document.querySelector('#infographics-settings'), status = document.querySelector('#infographics-status')) {
  const captured = session.capture(), controller = new AbortController();
  const fields = ['rosterPageSeconds','pointPageSeconds','leaderPageSeconds'];
  const fieldset = form.querySelector('fieldset');
  let stopped = false, unsubscribe = () => {};
  const current = () => !stopped && session.isCurrent(captured);
  const end = () => {
    if (stopped) return;
    stopped = true; controller.abort(); unsubscribe(); fieldset.disabled = true;
    fields.forEach(name => {form.elements[name].value = '';});
    status.textContent = 'Сессия изменилась. Откройте страницу заново';
  };
  unsubscribe = session.subscribe(() => {if (!current()) end();});
  addEventListener('pagehide', end, {once:true});
  const render = value => fields.forEach(name => {form.elements[name].value = value[name];});
  void readStaffJson(session, path, controller.signal).then(value => {
    if (current()) {render(value); fieldset.disabled = false;}
  }).catch(error => {if (current()) status.textContent = error.message;});
  form.addEventListener('submit', async event => {
    event.preventDefault(); if (!current() || fieldset.disabled) return;
    const body = Object.fromEntries(fields.map(name => [name, Number(form.elements[name].value)]));
    if (fields.some(name => form.elements[name].value === '' || !Number.isInteger(body[name]) || body[name] < 2 || body[name] > 15)) {
      status.textContent = 'Введите целые значения от 2 до 15'; return;
    }
    fieldset.disabled = true;
    try {
      const response = await session.protectedFetch(path, {method:'PUT', headers:{'Content-Type':'application/json'},
        signal:AbortSignal.any([controller.signal, AbortSignal.timeout(5000)]), body:JSON.stringify(body)});
      if (!current()) return;
      if ([401,403].includes(response.status)) {end(); return;}
      if (!response.ok) throw Error(`HTTP ${response.status}`);
      const saved = await response.json();
      if (current()) {render(saved); status.textContent = 'Сохранено. Интервалы действуют со следующей фазы';}
    } catch (error) {if (current()) status.textContent = error.message;}
    finally {if (current()) fieldset.disabled = false;}
  });
  return end;
}
